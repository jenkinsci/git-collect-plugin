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

import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.gitclient.ChangelogCommand;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
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
import hudson.model.listeners.SCMListener;
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

    /**
     * Optional base URL of the repository browser, for instance
     * {@code https://gerrithub.io/c/amarula/checks-jenkins} on a Gerrit without the gitiles plugin,
     * or {@code https://git.example.com/plugins/gitiles/amarula/checks-jenkins} where it is
     * installed. When unset, the browser is guessed from the remote URL, see
     * {@link GitRemoteUrl#looksLikeGerrit(String)}.
     */
    private String browserUrl;

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

    /**
     * Rewrites a Git remote URL into the base URL of the matching web repository browser.
     *
     * <p>SSH and scp-like remotes ({@code ssh://git@host:29418/project},
     * {@code git@host:project}) are converted to their HTTPS equivalent, remotes that already use
     * HTTP(S) keep their scheme, and a Gerrit web path in front of the {@code /a/} segment is
     * kept. See {@link GitRemoteUrl} for what is dropped on the way, and
     * {@link #setBrowserUrl(String)} for when the result is still not the right one.
     *
     * @param remoteUrl the URL configured on the git remote.
     * @param isGerrit  whether the remote is served by Gerrit, see
     *                  {@link GitRemoteUrl#looksLikeGerrit(String)}.
     * @return the browser base URL, or the remote URL unchanged when it has no host to build one
     *         from (local paths, {@code file://} remotes) or cannot be parsed.
     */
    static String convertToHttps(String remoteUrl, boolean isGerrit) {
        GitRemoteUrl remote = GitRemoteUrl.parse(remoteUrl);
        if (remote == null) {
            return remoteUrl;
        }
        return String.format("%s%s/%s", remote.getWebRoot(), isGerrit ? "/plugins/gitiles" : "",
                             isGerrit ? remote.getGerritProject() : remote.getProject());
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
     * {@link SCMListener#onCheckout} method to ensure that the changelog
     * is properly registered and visible in the Pipeline UI.
     *
     * @param run             The current workflow run.
     * @param targetDirectory The directory relative to the workspace that contains the Git repository,
     *                        or {@code null}/{@code ""} if the repository is at the workspace root.
     * @param workspace       The workspace root.
     * @param listener        The task listener.
     * @param url             The remote URL of the git repository.
     * @param changeLogPath   The path to the generated changelog XML file.
     * @throws IOException If an I/O error occurs.
     * @throws Exception   If any other error occurs during the listener invocation.
     */
    private GitSCM perfromAgainstWorkflowRun(WorkflowRun run, String targetDirectory, FilePath workspace,
                TaskListener listener, String url, String changeLogPath) throws IOException, Exception {
        GitSCM scm = new GitSCM(url);
        if (targetDirectory != null && !targetDirectory.trim().isEmpty()) {
            scm.getExtensions().add(new RelativeTargetDirectory(targetDirectory));
        }
        String configuredBrowserUrl = getBrowserUrl();
        boolean gerrit = GitRemoteUrl.looksLikeGerrit(url) || GitRemoteUrl.looksLikeGerrit(configuredBrowserUrl);
        GitRepositoryBrowser browser;

        if (configuredBrowserUrl != null) {
            // The user knows which web frontend serves this repository: use the URL as it is.
            browser = gerrit ? new Gitiles(configuredBrowserUrl) : new GithubWeb(configuredBrowserUrl);
        } else {
            browser = (GitRepositoryBrowser) scm.guessBrowser();

            if (browser == null) {
                String remoteBrowserUrl = convertToHttps(url, gerrit);
                browser = gerrit ? new Gitiles(remoteBrowserUrl) : new GithubWeb(remoteBrowserUrl);
            }
        }

        LOGGER.log(Level.FINE, "Changelog browser for " + url + ": "
                   + browser.getClass().getSimpleName() + " at " + browser.getRepoUrl());

        scm.setBrowser(browser);

        File changelogFile = new File(changeLogPath);
        for (SCMListener scmListener : SCMListener.all()) {
            try {
                scmListener.onCheckout(run, scm, workspace, listener, changelogFile, null);
            }
            catch (Exception exception) {
                LOGGER.log(Level.WARNING, "Failed to notify SCM listener '" + scmListener + "' of checkout", exception);
            }
        }
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
     * Sets the base URL of the repository browser used for the changelog links.
     *
     * @param browserUrl The browser base URL, without the trailing {@code +/<sha>} part.
     */
    @DataBoundSetter
    public void setBrowserUrl(String browserUrl) {
        this.browserUrl = browserUrl;
    }

    /**
     * Gets the configured base URL of the repository browser.
     *
     * @return The configured URL, or {@code null} when it has to be guessed from the remote URL.
     */
    public String getBrowserUrl() {
        if (browserUrl == null || browserUrl.trim().isEmpty()) {
            return null;
        }
        return browserUrl.trim();
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

        // Register the collected revision as GIT_COMMIT before notifying the SCM listeners. The
        // git-forensics GitCheckoutListener resolves the repository HEAD from GIT_COMMIT (falling
        // back to "HEAD"), so without this it would pick up a stale GIT_COMMIT left over from an
        // earlier checkout (e.g. a shared library) that does not exist in this repository.
        run.addAction(new MultiScmEnvAction(info));

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
                    scm = perfromAgainstWorkflowRun((WorkflowRun)run, this.path, workspace, listener,
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
