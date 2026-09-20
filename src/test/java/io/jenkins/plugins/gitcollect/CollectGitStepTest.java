package io.jenkins.plugins.gitcollect;

import org.junit.jupiter.api.Test;

import hudson.plugins.git.browser.GitRepositoryBrowser;
import hudson.plugins.git.browser.GithubWeb;
import hudson.plugins.git.browser.Gitiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void testConfiguredBrowserWins() {
        Gitiles configured = new Gitiles("https://gerrithub.io/c/amarula/checks-jenkins");
        GithubWeb guessed = new GithubWeb("https://github.com/amarula/checks-jenkins/");

        assertSame(configured, CollectGitStep.selectBrowser(configured, null, "https://github.com/a/b", guessed));
        assertSame(configured, CollectGitStep.selectBrowser(configured, "https://other.example.com/x",
                                                            "https://github.com/a/b", guessed));
    }

    @Test
    void testBrowserUrlBeatsTheGuess() {
        GithubWeb guessed = new GithubWeb("https://github.com/amarula/checks-jenkins/");

        GitRepositoryBrowser browser = CollectGitStep.selectBrowser(null, "https://gerrithub.io/c/amarula/checks-jenkins",
                                                                   "https://github.com/a/b", guessed);

        // The configured URL is used as it is, and its shape picks the gitiles link format.
        assertTrue(browser instanceof Gitiles);
        assertEquals("https://gerrithub.io/c/amarula/checks-jenkins", browser.getRepoUrl());
    }

    @Test
    void testGuessIsUsedWhenNothingIsConfigured() {
        GithubWeb guessed = new GithubWeb("https://github.com/amarula/checks-jenkins/");

        assertSame(guessed, CollectGitStep.selectBrowser(null, null, "https://github.com/a/b", guessed));
    }

    @Test
    void testFormatFollowsTheRemoteWhenNothingIsConfigured() {
        GitRepositoryBrowser gerrit = CollectGitStep.selectBrowser(null, null,
                "https://gerrithub.io/a/amarula/checks-jenkins", null);
        assertTrue(gerrit instanceof Gitiles);
        assertEquals("https://gerrithub.io/plugins/gitiles/amarula/checks-jenkins", gerrit.getRepoUrl());

        GitRepositoryBrowser other = CollectGitStep.selectBrowser(null, null, "git@git.example.com:team/b.git", null);
        assertTrue(other instanceof GithubWeb);
        assertEquals("https://git.example.com/team/b", other.getRepoUrl());
    }

    @Test
    void testProjectInAnADirectoryIsReadAsGerrit() {
        // The /a/ of a Gerrit clone path and a project that lives in an "a" directory are the same
        // thing on the wire, so the URL alone cannot tell them apart: browserUrl or browser is what
        // does. Pinned here so that the trade-off is a decision and not a surprise.
        GitRepositoryBrowser browser = CollectGitStep.selectBrowser(null, null, "git@git.example.com:a/b.git", null);

        assertTrue(browser instanceof Gitiles);
        assertEquals("https://git.example.com/plugins/gitiles/b", browser.getRepoUrl());
    }

    @Test
    void testBrowserUrlDefaultsToUnset() {
        CollectGitStep step = new CollectGitStep();

        assertNull(step.getBrowserUrl());
        assertNull(step.getBrowser());

        step.setBrowserUrl("  ");
        assertNull(step.getBrowserUrl());

        step.setBrowserUrl(" https://gerrithub.io/c/amarula/checks-jenkins ");
        assertEquals("https://gerrithub.io/c/amarula/checks-jenkins", step.getBrowserUrl());
    }
}
