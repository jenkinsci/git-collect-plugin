package io.jenkins.plugins.gitcollect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CollectGitStepTest {

    @Test
    void testHttpsRemoteIsNotMangled() {
        // Regression: the scp-like pattern used to read this as host "https", which produced
        // https://https/plugins/gitiles///gerrithub.io/a/amarula/checks-jenkins.
        assertEquals("https://gerrithub.io/plugins/gitiles/amarula/checks-jenkins",
                     CollectGitStep.convertToHttps("https://gerrithub.io/a/amarula/checks-jenkins", true));
    }

    @Test
    void testGerritRemoteConversion() {
        String expected = "https://gerrithub.io/plugins/gitiles/amarula/checks-jenkins";

        assertEquals(expected, CollectGitStep.convertToHttps("https://gerrithub.io/a/amarula/checks-jenkins.git", true));
        assertEquals(expected, CollectGitStep.convertToHttps("ssh://git@gerrithub.io:29418/a/amarula/checks-jenkins", true));
        assertEquals(expected, CollectGitStep.convertToHttps("git@gerrithub.io:a/amarula/checks-jenkins.git", true));
    }

    @Test
    void testGerritWebPathIsKept() {
        // The /r/ in front of /a/ is where this Gerrit serves its web UI from, dropping it points
        // the browse URL at the wrong place.
        assertEquals("https://gerrit.wikimedia.org/r/plugins/gitiles/mediawiki/core",
                     CollectGitStep.convertToHttps("https://gerrit.wikimedia.org/r/a/mediawiki/core", true));
    }

    @Test
    void testNonGerritRemoteConversion() {
        assertEquals("https://github.com/amarula/checks-jenkins",
                     CollectGitStep.convertToHttps("https://github.com/amarula/checks-jenkins.git", false));
        assertEquals("https://github.com/amarula/checks-jenkins",
                     CollectGitStep.convertToHttps("git@github.com:amarula/checks-jenkins.git", false));
        assertEquals("https://git.example.com/amarula/checks-jenkins",
                     CollectGitStep.convertToHttps("ssh://git@git.example.com/amarula/checks-jenkins.git", false));
    }

    @Test
    void testPortIsKeptOnlyForWebRemotes() {
        assertEquals("https://review.example.com:8443/plugins/gitiles/amarula/checks-jenkins",
                     CollectGitStep.convertToHttps("https://review.example.com:8443/a/amarula/checks-jenkins", true));
        // 29418 is the git port of a Gerrit, the web frontend is not listening there.
        assertEquals("https://review.example.com/plugins/gitiles/amarula/checks-jenkins",
                     CollectGitStep.convertToHttps("ssh://git@review.example.com:29418/a/amarula/checks-jenkins", true));
    }

    @Test
    void testRemotesWithoutHostAreLeftAlone() {
        assertEquals("/var/lib/jenkins/workspace/repo",
                     CollectGitStep.convertToHttps("/var/lib/jenkins/workspace/repo", false));
        assertEquals("C:\\repos\\checks-jenkins",
                     CollectGitStep.convertToHttps("C:\\repos\\checks-jenkins", false));
        assertEquals("file:///srv/git/checks-jenkins.git",
                     CollectGitStep.convertToHttps("file:///srv/git/checks-jenkins.git", true));
    }

    @Test
    void testBrowserUrlDefaultsToUnset() {
        CollectGitStep step = new CollectGitStep();

        assertNull(step.getBrowserUrl());

        step.setBrowserUrl("  ");
        assertNull(step.getBrowserUrl());

        step.setBrowserUrl(" https://gerrithub.io/c/amarula/checks-jenkins ");
        assertEquals("https://gerrithub.io/c/amarula/checks-jenkins", step.getBrowserUrl());
    }
}
