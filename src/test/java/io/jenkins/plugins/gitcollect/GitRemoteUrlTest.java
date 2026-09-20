package io.jenkins.plugins.gitcollect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GitRemoteUrlTest {

    @Test
    void testGerritAuthPathIsSplitFromTheWebRoot() {
        GitRemoteUrl plain = GitRemoteUrl.parse("https://gerrithub.io/a/amarula/checks-jenkins.git");
        assertEquals("https://gerrithub.io", plain.getWebRoot());
        assertEquals("a/amarula/checks-jenkins", plain.getProject());
        assertEquals("amarula/checks-jenkins", plain.getGerritProject());
        assertTrue(plain.hasAuthPath());

        GitRemoteUrl prefixed = GitRemoteUrl.parse("https://gerrit.wikimedia.org/r/a/mediawiki/core");
        assertEquals("https://gerrit.wikimedia.org/r", prefixed.getWebRoot());
        assertEquals("mediawiki/core", prefixed.getGerritProject());
    }

    @Test
    void testAuthSegmentIsNotJustAnySegmentNamedA() {
        // "a" as a path segment is only Gerrit's marker where it introduces the project; a segment
        // that merely contains one is not.
        GitRemoteUrl remote = GitRemoteUrl.parse("https://git.example.com/team/ab/repo");
        assertFalse(remote.hasAuthPath());
        assertEquals("team/ab/repo", remote.getProject());
    }

    @Test
    void testScpStyleRemote() {
        GitRemoteUrl remote = GitRemoteUrl.parse("git@gerrithub.io:a/amarula/checks-jenkins.git");
        assertEquals("https://gerrithub.io", remote.getWebRoot());
        assertEquals("amarula/checks-jenkins", remote.getGerritProject());
        assertEquals("gerrithub.io", remote.getHost());
        assertEquals(-1, remote.getPort());
    }

    @Test
    void testWebRootKeepsWebPortsOnly() {
        assertEquals("https://review.example.com:8443/r",
                     GitRemoteUrl.parse("https://review.example.com:8443/r/a/project").getWebRoot());
        // 29418 is where git talks, not where the web frontend listens.
        assertEquals("https://review.example.com",
                     GitRemoteUrl.parse("ssh://git@review.example.com:29418/project").getWebRoot());
    }

    @Test
    void testWebPathIsOnlyKnownWhenTheAuthPathNamesIt() {
        // An anonymous clone URL carries no marker to tell a web path from the project, so /r is
        // read as part of the project. That is what browserUrl is for.
        GitRemoteUrl remote = GitRemoteUrl.parse("https://gerrit.wikimedia.org/r/mediawiki/core");
        assertEquals("https://gerrit.wikimedia.org", remote.getWebRoot());
        assertEquals("r/mediawiki/core", remote.getProject());
    }

    @Test
    void testRemotesWithoutHostHaveNoWebRoot() {
        assertNull(GitRemoteUrl.parse("/var/lib/jenkins/workspace/repo"));
        assertNull(GitRemoteUrl.parse("file:///srv/git/repo.git"));
        assertNull(GitRemoteUrl.parse("C:\\repos\\checks-jenkins"));
        assertNull(GitRemoteUrl.parse(null));
        assertNull(GitRemoteUrl.parse("  "));
    }

    @Test
    void testLooksLikeGerrit() {
        assertTrue(GitRemoteUrl.looksLikeGerrit("https://gerrithub.io/a/amarula/checks-jenkins"));
        assertTrue(GitRemoteUrl.looksLikeGerrit("ssh://git@review.example.com:29418/amarula/checks-jenkins"));
        assertTrue(GitRemoteUrl.looksLikeGerrit("git@review.example.com:a/amarula/checks-jenkins.git"));
        assertTrue(GitRemoteUrl.looksLikeGerrit("https://review.example.com/plugins/gitiles/amarula/checks-jenkins"));
        assertTrue(GitRemoteUrl.looksLikeGerrit("https://gerrit.example.com/amarula/checks-jenkins"));

        assertFalse(GitRemoteUrl.looksLikeGerrit("https://github.com/amarula/checks-jenkins.git"));
        assertFalse(GitRemoteUrl.looksLikeGerrit("git@github.com:amarula/checks-jenkins.git"));
        // An organisation named gerrit is not a Gerrit host.
        assertFalse(GitRemoteUrl.looksLikeGerrit("https://github.com/gerrit/checks-jenkins.git"));
        assertFalse(GitRemoteUrl.looksLikeGerrit(null));
    }
}
