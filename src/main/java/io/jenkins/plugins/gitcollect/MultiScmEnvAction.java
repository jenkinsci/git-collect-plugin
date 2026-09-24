package io.jenkins.plugins.gitcollect;

import hudson.EnvVars;
import hudson.model.EnvironmentContributingAction;
import hudson.model.Run;

public class MultiScmEnvAction implements EnvironmentContributingAction {
    private final LocalGitInfo info;

    public MultiScmEnvAction(LocalGitInfo info) {
        this.info = info;
    }

    /**
     * Gets the repository data this action contributes to the build environment.
     *
     * <p>It also says which repository the build collected, since one action is added for each
     * collection: see {@link CollectGitStep#alreadyCollected(Run, LocalGitInfo)}.
     *
     * @return the data collected from the repository.
     */
    public LocalGitInfo getInfo() {
        return info;
    }

    @Override
    public void buildEnvironment(Run<?, ?> run, EnvVars env) {
        env.put("GIT_COMMIT", info.getShaRevision());
        env.put("GIT_BRANCH", info.getBranch());
        env.put("GIT_URL", info.getRemoteUrl());
        env.put("GIT_REMOTE", info.getRemote());

        String safeName = info.getScmName().replaceAll("[^a-zA-Z0-9_]", "_");

        env.put("GIT_COMMIT_" + safeName, info.getShaRevision());
        env.put("GIT_BRANCH_" + safeName, info.getBranch());
        env.put("GIT_URL_" + safeName, info.getRemoteUrl());
        env.put("GIT_REMOTE_" + safeName, info.getRemote());
    }

    @Override
    public String getIconFileName() { return null; }
    @Override
    public String getDisplayName() { return null; }
    @Override
    public String getUrlName() { return null; }
}
