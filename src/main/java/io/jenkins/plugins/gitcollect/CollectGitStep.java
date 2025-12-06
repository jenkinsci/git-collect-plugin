package io.jenkins.plugins.gitcollect;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildData;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.eclipse.jgit.lib.ObjectId;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

public class CollectGitStep extends Builder implements SimpleBuildStep {

    private String path;
    private String markedCommit; // Optional: A branch name (e.g. "master") or SHA

    /**
     * Checks if the given directory is a valid Git repository.
     */
    private boolean isGitRepository(GitClient git) {
        try {
           git.revParse("--git-dir");
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

        listener.getLogger().println("[GitCollect] Analyzing repository at: " + gitDir.getRemote());

        ObjectId resolvedObjectId;
        try {
            resolvedObjectId = git.revParse(targetStr);
            listener.getLogger().println("[GitCollect] Resolved '" + targetStr + "' to SHA: " + resolvedObjectId.name());
        } catch (GitException e) {
            throw new IOException("[GitCollect] Could not resolve revision '" + targetStr + "'", e);
        }

        Revision builtRevision = new Revision(resolvedObjectId);
        Revision markedRevision = new Revision(resolvedObjectId);

        // If the user input is NOT a raw SHA1 (meaning it is a branch name or tag),
        // we attach that name to the Marked Revision so the UI displays "origin/master" etc.
        if (markedCommit != null && !ObjectId.isId(markedCommit)) {
            Collection<Branch> branches = new ArrayList<>();
            branches.add(new Branch(markedCommit, resolvedObjectId));
            markedRevision.setBranches(branches);
        }

        Result result = run.getResult();
        if (result == null) {
            result = Result.SUCCESS; // Default to Success if running
        }

        BuildData buildData = new BuildData();
        buildData.addRemoteUrl(gitDir.getRemote()); // Helps Jenkins identify the repo

        Build gitBuild = new Build(markedRevision, builtRevision, run.getNumber(), result);
        buildData.saveBuild(gitBuild);

        run.addAction(buildData);
        listener.getLogger().println("[GitCollect] BuildData attached. Marked: " + targetStr + ", Built: " + resolvedObjectId.name());
    }

    @Symbol("collectGit") // Allows: collectGit path: 'src', markedCommit: 'master'
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
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
