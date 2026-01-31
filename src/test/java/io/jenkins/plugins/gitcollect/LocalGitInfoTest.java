package io.jenkins.plugins.gitcollect;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import hudson.plugins.git.Branch;
import hudson.plugins.git.Revision;

import java.util.ArrayList;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LocalGitInfoTest {

    @Test
    void testGetBranchAndShaRevision() {
        ObjectId id = ObjectId.fromString("1111111111111111111111111111111111111111");
        Revision built = new Revision(id);

        Collection<Branch> branches = new ArrayList<>();
        branches.add(new Branch("main", id));
        built.setBranches(branches);

        Revision marked = new Revision(id);

        LocalGitInfo info = new LocalGitInfo("scm", "ssh://example/repo.git", built, marked);

        assertEquals("main", info.getBranch());
        assertEquals(id.getName(), info.getShaRevision());
        assertEquals("ssh://example/repo.git", info.getRemoteUrl());
        assertEquals("scm", info.getScmName());
    }
}
