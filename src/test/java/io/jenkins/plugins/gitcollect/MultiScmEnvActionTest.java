package io.jenkins.plugins.gitcollect;

import hudson.EnvVars;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import hudson.plugins.git.Branch;
import hudson.plugins.git.Revision;

import java.util.ArrayList;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MultiScmEnvActionTest {

    @Test
    void testBuildEnvironmentAddsVars() {
        ObjectId id = ObjectId.fromString("2222222222222222222222222222222222222222");
        Revision built = new Revision(id);
        Collection<Branch> branches = new ArrayList<>();
        branches.add(new Branch("feature/x", id));
        built.setBranches(branches);

        Revision marked = new Revision(id);

        LocalGitInfo info = new LocalGitInfo("my/SCM-name", "ssh://git@example.com:29418/repo.git", built, marked);

        MultiScmEnvAction action = new MultiScmEnvAction(info);

        EnvVars env = new EnvVars();
        action.buildEnvironment(null, env);

        assertEquals(id.getName(), env.get("GIT_COMMIT"));
        assertEquals("feature/x", env.get("GIT_BRANCH"));
        assertEquals("ssh://git@example.com:29418/repo.git", env.get("GIT_URL"));

        String safeName = "my_SCM_name"; // my/SCM-name -> my_SCM_name
        assertEquals(id.getName(), env.get("GIT_COMMIT_" + safeName));
        assertEquals("feature/x", env.get("GIT_BRANCH_" + safeName));
    }
}
