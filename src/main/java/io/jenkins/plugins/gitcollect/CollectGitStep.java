package io.jenkins.plugins.gitcollect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.gitclient.ChangelogCommand;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.WorkflowRun.SCMListenerImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.browser.GitLab;
import hudson.plugins.git.browser.GitRepositoryBrowser;
import hudson.plugins.git.browser.GithubWeb;
import hudson.plugins.git.browser.Gitiles;
import hudson.plugins.git.extensions.impl.RelativeTargetDirectory;
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildData;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jakarta.annotation.Nonnull;
import jenkins.tasks.SimpleBuildStep;

/**
 * A Jenkins build step that collects Git repository information from a local workspace directory
 * and registers it with the current build.
 *
 * <p>This step allows a build to recognize a directory as a Git repository even if the
 * checkout was not performed by the standard Git SCM plugin during the current stage
 * (e.g., if the repo was generated, restored from cache, or checked out by a script).
 *
 * <p>It populates {@link BuildData} actions, allowing subsequent steps to access Git
 * revision data and optionally generating changelogs.
 */
public class CollectGitStep extends Builder implements SimpleBuildStep {

    private static final Pattern SSH_URI_PATTERN = Pattern.compile("^ssh://(?:[^@]+@)?([^:/]+)(?::\\d+)?/(.+)$");
    private static final Pattern SCP_STYLE_PATTERN = Pattern.compile("^(?:[^@]+@)?([^:/]+):(.+)$");

    /**
     * The relative path to the Git repository within the workspace.
     * If null or empty, the workspace root is assumed.
     */
    private String path;

    /**
     * Optional reference (branch name or SHA) used as the "marked" revision (the previous baseline).
     * Defaults to "HEAD" if not specified.
     */
    private String markedCommit;

    /**
     * Optional remote name used to work with checkout detach branch.
     * Defaults to "origin" if not specified
     */
    private String remote;

    /**
     * Flag indicating whether to generate a changelog between the marked revision and the current revision.
     */
    private Boolean changelog = false;

    public static final Logger LOGGER = Logger.getLogger(CollectGitStep.class.getName());

    /**
     * Checks if the given Git client points to a valid repository.
     *
     * @param git the {@link GitClient} instance to test.
     * @return {@code true} if the directory is a valid git repository (can list revisions); {@code false} otherwise.
     */
    private boolean isGitRepository(GitClient git) {
        try {
           git.revListAll();
           return true;
        } catch (GitException | InterruptedException e) {
           return false;
        }
    }

    private static String convertToHttps(String sshUrl, boolean isGerrit) {
        if (sshUrl == null || sshUrl.isEmpty()) {
            return sshUrl;
        }

        // Check for URI style (ssh://...) first as it's more specific
        Matcher uriMatcher = SSH_URI_PATTERN.matcher(sshUrl);
        if (uriMatcher.find()) {
            return String.format("https://%s%s/%s", uriMatcher.group(1), isGerrit == true ? "/plugins/gitiles" : "",
                                 uriMatcher.group(2));
        }

        // Check for SCP style (git@...)
        Matcher scpMatcher = SCP_STYLE_PATTERN.matcher(sshUrl);
        if (scpMatcher.find()) {
            return String.format("https://%s%s/%s", scpMatcher.group(1), isGerrit == true ? "/plugins/gitiles" : "",
                                 scpMatcher.group(2));
        }

        return sshUrl;
    }

    /**
     * Generates a standard Jenkins XML changelog file.
     *
     * <p>Calculates the difference between the {@code builtRevision} and the {@code markedRevision}
     * found in the {@link LocalGitInfo} and writes it to a temporary file.
     *
     * @param run  The current build run.
     * @param git  The git client initialized for the target directory.
     * @param info The collected git information containing revision data.
     * @return The absolute path to the generated changelog file, or {@code null} if generation failed.
     * @throws IOException If an I/O error occurs during file creation or writing.
     */
    private String writeChangelog(@Nonnull Run<?, ?> run, GitClient git, LocalGitInfo info) throws IOException {
        File changelogFile = null;

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            changelogFile = Files.createTempFile(run.getRootDir().toPath(), "changelog", ".xml",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--"))).toFile();
        } else {
            changelogFile = Files.createTempFile(run.getRootDir().toPath(), "changelog", ".xml").toFile();
        }

        if (changelogFile != null) {
            ChangelogCommand changelog = git.changelog();
            OutputStream stream = new FileOutputStream(changelogFile);

            Writer out = new OutputStreamWriter(stream, StandardCharsets.UTF_8);

            try {
                changelog.includes(info.getBuiltRevision().getSha1String())
                    .excludes(info.getMarkedRevision().getSha1String())
                    .to(out)
                    .execute();
            } catch (GitException | InterruptedException e) {
                Files.deleteIfExists(changelogFile.toPath());
                e.printStackTrace();
            }

            return changelogFile.getAbsolutePath();
        }

        return null;
    }

    /**
     * Manually triggers the SCM checkout listeners for Workflow (Pipeline) runs.
     *
     * <p>This method constructs a temporary {@link GitSCM} instance and invokes the
     * {@link SCMListenerImpl#onCheckout} method to ensure that the changelog
     * is properly registered and visible in the Pipeline UI.
     *
     * @param run           The current workflow run.
     * @param gitDir        The FilePath to the git repository.
     * @param workspace     The workspace root.
     * @param listener      The task listener.
     * @param url           The remote URL of the git repository.
     * @param changeLogPath The path to the generated changelog XML file.
     * @throws IOException If an I/O error occurs.
     * @throws Exception   If any other error occurs during the listener invocation.
     */
    private GitSCM perfromAgainstWorkflowRun(WorkflowRun run, FilePath gitDir, FilePath workspace,
                TaskListener listener, String url, String changeLogPath) throws IOException, Exception {
        SCMListenerImpl scmListenerImpl = new WorkflowRun.SCMListenerImpl();

        GitSCM scm = new GitSCM(url);
        scm.getExtensions().add(new RelativeTargetDirectory(gitDir.getRemote()));
        GitRepositoryBrowser browser = (GitRepositoryBrowser) scm.guessBrowser();

        if (browser == null) {
            if (url.contains("gerrit")) {
                browser = new Gitiles(convertToHttps(url, true));
            } else {
                browser = new GithubWeb(convertToHttps(url, false));
            }
        }

        scm.setBrowser(browser);

        scmListenerImpl.onCheckout(run, scm, workspace, listener,
                                   new File(changeLogPath), null);
        return scm;
    }

    /**
     * Default constructor for DataBound instantiation.
     */
    @DataBoundConstructor
    public CollectGitStep() {
    }

    /**
     * Sets the relative path to the git directory.
     *
     * @param path The path relative to the workspace root.
     */
    @DataBoundSetter
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Gets the relative path to the git directory.
     *
     * @return The path, or null.
     */
    public String getPath() {
        return path;
    }

    /**
     * Sets whether to generate a changelog.
     *
     * @param changelog {@code true} to enable changelog generation.
     */
    @DataBoundSetter
    public void setChangelog(boolean changelog) {
        this.changelog = changelog;
    }

    /**
     * Gets the changelog generation flag.
     *
     * @return {@code true} if changelog generation is enabled.
     */
    public Boolean getChangelog() {
        return this.changelog;
    }

    /**
     * Sets the specific remote name (origin, m, ...).
     *
     * @param remote Set the remote name.
     */
    @DataBoundSetter
    public void setRemote(String remote) {
        this.remote = remote;
    }

    /**
     * Gets the remote branch.
     *
     * @return The return the remote name.
     */
    public String getRemote() {
        if (remote == null || remote.isEmpty()) {
            return "origin";
        }
        return remote;
    }

    /**
     * Sets the specific commit or branch to mark as the previous baseline.
     *
     * @param markedCommit A SHA1 hash or branch name (e.g., "master").
     */
    @DataBoundSetter
    public void setMarkedCommit(String markedCommit) {
        this.markedCommit = markedCommit;
    }

    /**
     * Gets the marked commit reference.
     *
     * @return The marked commit string.
     */
    public String getMarkedCommit() {
        return markedCommit;
    }

    /**
     * Executes the build step.
     *
     * <p>This method performs the following actions:
     * <ol>
     * <li>Resolves the Git directory path.</li>
     * <li>Validates that the directory is a Git repository.</li>
     * <li>Scans the repository using {@link GitScanner} to retrieve remote URLs and revisions.</li>
     * <li>Optionally generates a changelog if requested and differences are found.</li>
     * <li>Creates a {@link BuildData} object and attaches it to the run actions.</li>
     * <li>Attaches {@link MultiScmEnvAction} for environment variable contribution.</li>
     * </ol>
     *
     * @param run       The current build.
     * @param workspace The project workspace.
     * @param launcher  The launcher.
     * @param listener  The build listener for logging.
     * @throws InterruptedException If the operation is interrupted.
     * @throws IOException          If an I/O error occurs (e.g., repo not found).
     */
    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace,
                        @Nonnull Launcher launcher, @Nonnull TaskListener listener)
                        throws InterruptedException, IOException {

        FilePath gitDir = (path == null || path.trim().isEmpty())
                          ? workspace
                          : workspace.child(path);

        if (!gitDir.exists()) {
            throw new IOException("[GitCollect] Error: Path not found: " + gitDir.getRemote());
        }

        EnvVars env = run.getEnvironment(listener);
        GitClient git = Git.with(listener, env).in(gitDir).getClient();

        if (!isGitRepository(git)) {
            throw new IOException("[GitCollect] Error: The directory '" + gitDir.getRemote() + "' is not a valid Git repository.");
        }

        String targetStr = (markedCommit != null && !markedCommit.trim().isEmpty())
                           ? markedCommit
                           : "HEAD";

        LOGGER.log(Level.FINE, "Analyzing repository at: " + gitDir.getRemote());
        LocalGitInfo info = workspace.act(new GitScanner(git, markedCommit, getRemote()));

        LOGGER.log(Level.FINE, "url: " + info.getRemoteUrl() + " branch: " + info.getBranch());

        Result result = run.getResult();
        if (result == null) {
            result = Result.SUCCESS;
        }

        GitSCM scm = null;

        if (changelog && !info.getMarkedRevision().getSha1String().equals(
            info.getBuiltRevision().getSha1String())) {
            String path = writeChangelog(run, git, info);
            if (path != null && !path.isEmpty() && run instanceof WorkflowRun) {
                try {
                    scm = perfromAgainstWorkflowRun((WorkflowRun)run, gitDir, workspace, listener,
                                              info.getRemoteUrl(), path);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        BuildData buildData = new BuildData();

        if (scm != null) {
            buildData = scm.copyBuildData(run.getPreviousBuild());
        }

        if (buildData.lastBuild != null) {
            LOGGER.log(Level.FINE, "Last Built Revision: " + buildData.lastBuild.revision);
        }

        buildData.addRemoteUrl(info.getRemoteUrl());
        Build gitBuild = new Build(info.getMarkedRevision(), info.getBuiltRevision(), run.getNumber(), result);
        buildData.saveBuild(gitBuild);

        // Track whether we're trying to add a duplicate BuildData, now that it's been updated with
        // revision info for this build etc. The default assumption is that it's a duplicate.
        boolean buildDataAlreadyPresent = false;
        List<BuildData> actions = run.getActions(BuildData.class);
        for (BuildData d: actions)  {
            if (d.similarTo(buildData)) {
                buildDataAlreadyPresent = true;
                break;
            }
        }
        if (!actions.isEmpty()) {
            buildData.setIndex(actions.size()+1);
        }

        // If the BuildData is not already attached to this build, add it to the build and mark that
        // it wasn't already present, so that we add the GitTagAction and changelog after the checkout
        // finishes.
        if (!buildDataAlreadyPresent) {
            run.addAction(buildData);
            run.addAction(new MultiScmEnvAction(info));
        }

        LOGGER.log(Level.FINE, "BuildData attached. Marked: " + targetStr + ", Built: " + info.getShaRevision());
    }

    @Symbol("collectGit") // Allows: collectGit path: 'src', markedCommit: 'master'
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        @SuppressWarnings("rawtypes")
        public boolean isApplicable(final Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Git Collect: Register Local Data";
        }

        @POST
        public FormValidation doCheckPath(@QueryParameter String value) {
            if (value.startsWith("/")) {
                return FormValidation.warning("Paths should usually be relative to the workspace.");
            }
            return FormValidation.ok();
        }
    }
}
