package io.jenkins.plugins.gitcollect;

import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.WorkflowRun.SCMListenerImpl;

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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.gitclient.ChangelogCommand;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

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
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.impl.RelativeTargetDirectory;
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildData;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;

public class CollectGitStep extends Builder implements SimpleBuildStep {

    private String path;
    private String markedCommit; // Optional: A branch name (e.g. "master") or SHA
    private Boolean changelog = false;

    public static final Logger LOGGER = Logger.getLogger(CollectGitStep.class.getName());

    /**
     * Checks if the given directory is a valid Git repository.
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
     * Write changelog function
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
     * Performs the necessary logic against Pipeline jobs.
     */
    private void perfromAgainstWorkflowRun(WorkflowRun run, FilePath gitDir, FilePath workspace,
                TaskListener listener, String url, String changeLogPath) throws IOException, Exception {
        SCMListenerImpl scmListenerImpl = new WorkflowRun.SCMListenerImpl();

        GitSCM scm = new GitSCM(url);
        scm.getExtensions().add(new RelativeTargetDirectory(gitDir.getRemote()));

        scmListenerImpl.onCheckout(run, scm, workspace, listener,
                                   new File(changeLogPath), null);
    }

    @DataBoundConstructor
    public CollectGitStep() {
    }

    @DataBoundSetter
    public void setPath(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    @DataBoundSetter
    public void setChangelog(boolean changelog) {
        this.changelog = changelog;
    }

    public Boolean getChangelog() {
        return this.changelog;
    }

    @DataBoundSetter
    public void setMarkedCommit(String markedCommit) {
        this.markedCommit = markedCommit;
    }

    public String getMarkedCommit() {
        return markedCommit;
    }

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

        LOGGER.log(Level.INFO, "Analyzing repository at: " + gitDir.getRemote());
        LocalGitInfo info = workspace.act(new GitScanner(git, markedCommit));

        LOGGER.log(Level.FINE, "url: " + info.getRemoteUrl() + " branch: " + info.getBranch());

        Result result = run.getResult();
        if (result == null) {
            result = Result.SUCCESS;
        }

        if (changelog && !info.getMarkedRevision().getSha1String().equals(
            info.getBuiltRevision().getSha1String())) {
            String path = writeChangelog(run, git, info);
            if (path != null && !path.isEmpty() && run instanceof WorkflowRun) {
                try {
                    perfromAgainstWorkflowRun((WorkflowRun)run, gitDir, workspace, listener,
                                              info.getRemoteUrl(), path);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        BuildData buildData = new BuildData();
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

        public FormValidation doCheckPath(@QueryParameter String value) {
            if (value.startsWith("/")) {
                return FormValidation.warning("Paths should usually be relative to the workspace.");
            }
            return FormValidation.ok();
        }
    }
}
