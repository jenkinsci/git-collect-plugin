package io.jenkins.plugins.gitcollect;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import hudson.model.Action;
import hudson.model.Run;
import hudson.plugins.git.Revision;
import hudson.plugins.git.browser.GitRepositoryBrowser;
import hudson.plugins.git.browser.GithubWeb;
import hudson.plugins.git.browser.Gitiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CollectGitStepTest {

    private static final String SHA1 = "1111111111111111111111111111111111111111";
    private static final String SHA2 = "2222222222222222222222222222222222222222";

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

    /**
     * Builds the data a collection reads out of a repository.
     *
     * @param remoteUrl the URL configured on the remote of the repository.
     * @param sha1      the revision the repository is at.
     * @return the collected data.
     */
    private static LocalGitInfo collected(String remoteUrl, String sha1) {
        ObjectId id = ObjectId.fromString(sha1);
        return new LocalGitInfo("travel-smart", remoteUrl, "origin", new Revision(id), new Revision(id));
    }

    /**
     * Builds a run whose actions behave like the real ones, so that a repository collected once is
     * found again by the calls that follow, as it is on a real build.
     *
     * @param actions the actions the run starts with.
     * @return the run.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Run<?, ?> runWith(List<MultiScmEnvAction> actions) {
        Run run = mock(Run.class);
        when(run.getActions(MultiScmEnvAction.class)).thenAnswer(call -> new ArrayList<>(actions));
        doAnswer(call -> {
            actions.add(call.getArgument(0));
            return null;
        }).when(run).addAction(any(Action.class));
        return run;
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void testHistoryCollectedTwiceIsCollectedOnce() {
        List<MultiScmEnvAction> actions = new CopyOnWriteArrayList<>();
        Run run = runWith(actions);
        String url = "ssh://git@gerrit:29418/amarula-app/travel-smart";

        assertTrue(CollectGitStep.markCollected(run, collected(url, SHA1)));
        // The second call reads the same history, and registering it again would list it twice in
        // the build.
        assertFalse(CollectGitStep.markCollected(run, collected(url, SHA1)));

        assertEquals(1, actions.size());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void testParallelBranchesCollectTheHistoryOnce() throws Exception {
        List<MultiScmEnvAction> actions = new CopyOnWriteArrayList<>();
        Run run = runWith(actions);
        LocalGitInfo info = collected("ssh://git@gerrit:29418/amarula-app/travel-smart", SHA1);

        int branches = 8;
        ExecutorService pool = Executors.newFixedThreadPool(branches);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> collected = new ArrayList<>();
        try {
            for (int branch = 0; branch < branches; branch++) {
                collected.add(pool.submit(() -> {
                    start.await();
                    return CollectGitStep.markCollected(run, info);
                }));
            }
            start.countDown();

            int winners = 0;
            for (Future<Boolean> branch : collected) {
                if (branch.get(30, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            // Exactly one branch registers the history, the others find it done.
            assertEquals(1, winners);
            assertEquals(1, actions.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void testAnotherRevisionOfTheRepositoryIsCollectedAsWell() {
        List<MultiScmEnvAction> actions = new CopyOnWriteArrayList<>();
        Run run = runWith(actions);
        String url = "ssh://git@gerrit:29418/amarula-app/travel-smart";

        assertTrue(CollectGitStep.markCollected(run, collected(url, SHA1)));
        // Two revisions are two histories, and a build that collects both records both.
        assertTrue(CollectGitStep.markCollected(run, collected(url, SHA2)));

        assertEquals(2, actions.size());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void testAnotherRepositoryIsCollectedAsWell() {
        List<MultiScmEnvAction> actions = new CopyOnWriteArrayList<>();
        Run run = runWith(actions);

        assertTrue(CollectGitStep.markCollected(run, collected("ssh://git@gerrit:29418/amarula-app/travel-smart", SHA1)));
        assertTrue(CollectGitStep.markCollected(run, collected("ssh://git@gerrit:29418/amarula-app/checks-jenkins", SHA1)));

        assertEquals(2, actions.size());
    }

    @Test
    void testSameHistoryIsRecognizedHoweverTheUrlIsSpelled() {
        assertTrue(CollectGitStep.sameHistory(collected("ssh://git@gerrit:29418/amarula-app/travel-smart", SHA1),
                                              collected("ssh://git@gerrit:29418/amarula-app/travel-smart", SHA1)));
        assertTrue(CollectGitStep.sameHistory(collected("https://gerrit/amarula-app/travel-smart.git", SHA1),
                                              collected("https://gerrit/amarula-app/travel-smart/", SHA1)));
        assertFalse(CollectGitStep.sameHistory(collected("https://gerrit/amarula-app/travel-smart", SHA1),
                                               collected("https://gerrit/amarula-app/checks-jenkins", SHA1)));
        assertFalse(CollectGitStep.sameHistory(collected("https://gerrit/amarula-app/travel-smart", SHA1),
                                               collected("https://gerrit/amarula-app/travel-smart", SHA2)));
        // A repository no remote names is not the same repository as another one.
        assertFalse(CollectGitStep.sameHistory(collected("", SHA1), collected("", SHA1)));
    }
}
