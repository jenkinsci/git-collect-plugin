package io.jenkins.plugins.gitcollect;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jgit.transport.URIish;

/**
 * The parts of a git remote URL that a web frontend URL is built from.
 *
 * <p>A remote URL says where <i>git</i> talks to, which is not where a browser goes: the scheme
 * differs ({@code ssh} against {@code https}), an SSH port is not the port the web frontend
 * listens on, the {@code .git} suffix is not part of a web path, and Gerrit's authenticated clone
 * path adds an {@code /a/} segment that its web UI answers 401 on. Everything in front of that
 * segment is the web root, which is not always the host root:
 *
 * <pre>
 * ssh://git@host:29418/a/project.git   -&gt;  web root https://host,     project project
 * https://host/r/a/project             -&gt;  web root https://host/r,   project project
 * git@host:project.git                 -&gt;  web root https://host,     project project
 * </pre>
 *
 * <p>The first {@code a} segment wins when splitting: that is where Gerrit puts it, but it does
 * make a project that has an {@code a} directory of its own indistinguishable from one that does
 * not. {@link #looksLikeGerrit(String)} cannot tell those apart either, which is what
 * {@link CollectGitStep#setBrowserUrl(String)} is for.
 */
final class GitRemoteUrl {

    /**
     * Path segment Gerrit puts in front of the project on its authenticated clone URLs.
     */
    private static final String GERRIT_AUTH_SEGMENT = "a";

    /**
     * Path of the gitiles plugin of a Gerrit: the browse URLs only exist there.
     */
    private static final String GITILES_PATH = "plugins/gitiles";

    private static final Logger LOGGER = Logger.getLogger(GitRemoteUrl.class.getName());

    private final String scheme;
    private final String host;
    private final int port;
    private final String webPath;
    private final String project;
    private final String gerritProject;
    private final boolean authPath;

    private GitRemoteUrl(String scheme, String host, int port, String webPath, String project,
                         String gerritProject, boolean authPath) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.webPath = webPath;
        this.project = project;
        this.gerritProject = gerritProject;
        this.authPath = authPath;
    }

    /**
     * Splits a remote URL into the parts a web frontend URL is built from.
     *
     * @param remoteUrl the URL configured on the git remote.
     * @return the parsed URL, or {@code null} when there is no host to build a web URL from (a
     *         local directory, a {@code file://} remote) or the URL cannot be parsed at all.
     */
    static GitRemoteUrl parse(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.trim().isEmpty()) {
            return null;
        }

        URIish remote;
        try {
            remote = new URIish(remoteUrl.trim());
        } catch (URISyntaxException e) {
            LOGGER.log(Level.FINE, "Not a remote a web frontend URL can be built from: " + remoteUrl, e);
            return null;
        }

        String host = remote.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }

        String path = stripGitSuffix(trimSlashes(remote.getPath()));
        String[] segments = path.isEmpty() ? new String[0] : path.split("/");
        int auth = indexOf(segments, GERRIT_AUTH_SEGMENT);
        String webPath = auth > 0 ? String.join("/", Arrays.copyOfRange(segments, 0, auth)) : "";
        String gerritProject = auth >= 0
                               ? String.join("/", Arrays.copyOfRange(segments, auth + 1, segments.length))
                               : path;

        return new GitRemoteUrl(remote.getScheme(), host, remote.getPort(), webPath,
                                path, gerritProject, auth >= 0);
    }

    /**
     * Tells whether a URL is shaped like a Gerrit remote, which is what decides between the
     * gitiles browse URLs and the GitHub ones.
     *
     * <p>This looks at the URL alone, so it only recognises the Gerrits that say so: a host named
     * after it, the {@code /a/} clone path, the gitiles browse path or the SSH port 29418. A
     * Gerrit served from somewhere else, cloned over anonymous https, looks like any other host -
     * there {@link CollectGitStep#setBrowserUrl(String)} is what tells the two apart.
     *
     * @param remoteUrl the URL configured on the git remote, or a configured browser URL.
     * @return {@code true} when the URL is shaped like a Gerrit remote.
     */
    static boolean looksLikeGerrit(String remoteUrl) {
        GitRemoteUrl remote = parse(remoteUrl);
        if (remote == null) {
            // Unparseable, so all that is left is the "gerrit" in the name.
            return remoteUrl != null && remoteUrl.contains("gerrit");
        }
        return remote.hasGerritHost() || remote.hasAuthPath()
               || remote.hasGitilesPath() || remote.getPort() == 29418;
    }

    /**
     * @return the authority the web frontend is served from, always over https because the links
     *         are followed from the Jenkins UI.
     */
    String getWebRoot() {
        StringBuilder root = new StringBuilder("https://").append(host);
        if (port > 0 && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            // The port of a ssh remote is the git port, not the one the web frontend listens on.
            root.append(':').append(port);
        }
        if (!webPath.isEmpty()) {
            root.append('/').append(webPath);
        }
        return root.toString();
    }

    /**
     * @return the project as git names it, without the {@code .git} suffix.
     */
    String getProject() {
        return project;
    }

    /**
     * @return the project as the web frontend names it, that is without the {@code .git} suffix and
     *         without the Gerrit {@code /a/} segment and the web path in front of it.
     */
    String getGerritProject() {
        return gerritProject;
    }

    String getHost() {
        return host;
    }

    int getPort() {
        return port;
    }

    /**
     * @return {@code true} when the URL walks Gerrit's authenticated clone path.
     */
    boolean hasAuthPath() {
        return authPath;
    }

    /**
     * @return {@code true} when a label of the host names Gerrit, as in {@code gerrit.example.com}
     *         or {@code gerrithub.io}. Only the host counts: an organisation or a project called
     *         gerrit on github.com is not a Gerrit.
     */
    boolean hasGerritHost() {
        for (String label : host.split("[.]")) {
            String name = label.toLowerCase(Locale.ROOT);
            if (name.contains("gerrit") || name.contains("gitiles")) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} when the path is a Gerrit gitiles browse URL.
     */
    boolean hasGitilesPath() {
        return project.equals(GITILES_PATH) || project.startsWith(GITILES_PATH + "/")
               || project.contains("/" + GITILES_PATH);
    }

    private static int indexOf(String[] segments, String segment) {
        for (int i = 0; i < segments.length; i++) {
            if (segment.equals(segments[i])) {
                return i;
            }
        }
        return -1;
    }

    private static String trimSlashes(String path) {
        return path == null ? "" : path.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static String stripGitSuffix(String project) {
        return project.endsWith(".git") ? project.substring(0, project.length() - 4) : project;
    }
}
