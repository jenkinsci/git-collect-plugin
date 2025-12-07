package io.jenkins.plugins.gitcollect;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import org.jenkinsci.Symbol;
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
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildData;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;

public class CollectGitStep extends Builder implements SimpleBuildStep {

    private String path;
    private String markedCommit; // Optional: A branch name (e.g. "master") or SHA

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
