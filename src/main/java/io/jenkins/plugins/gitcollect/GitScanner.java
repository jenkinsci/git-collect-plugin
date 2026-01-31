package io.jenkins.plugins.gitcollect;

import jenkins.MasterToSlaveFileCallable;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.GitClient;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.Revision;
import hudson.remoting.VirtualChannel;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A file callable that executes on the agent (slave) to extract Git repository information.
 *
 * <p>This class extends {@link MasterToSlaveFileCallable}, allowing it to be sent from the
 * Jenkins controller to the agent where the workspace resides. It interacts with the
 * local file system using the {@link GitClient} to resolve revisions and remote URLs.
 *
 * <p>It returns a {@link LocalGitInfo} object containing serializable data about the
 * repository state, which is then sent back to the controller.
 */
@SuppressFBWarnings(value = "SE", justification = "GitClient implementation is Serializable")
public class GitScanner extends MasterToSlaveFileCallable<LocalGitInfo> {
    private static final long serialVersionUID = 1L;

    /**
     * The Git client used to execute commands against the repository.
     */
    private final GitClient git;

    /**
     * The optional reference (SHA1 or branch name) used as the baseline "marked" revision.
     * If null, "HEAD" is used.
     */
    private final String markedCommit;

    /**
     * The optional reference remote name.
     * If null, "origin" is used.
     */
    private final String remote;

    public static final Logger LOGGER = Logger.getLogger(GitScanner.class.getName());

    /**
     * Constructs a new GitScanner.
     *
     * @param git          The {@link GitClient} initialized for the specific workspace directory.
     * @param markedCommit A specific commit hash or branch name to resolve as the "marked" revision.
     * @param remote Specify the remote name
     */
    public GitScanner(GitClient git, String markedCommit, String remote) {
        this.git = git;
        this.markedCommit = markedCommit;
        this.remote = remote;
    }

    /**
     * Executes the scanning logic in the workspace.
     *
     * <p>This method performs the following steps:
     * <ol>
     * <li>Retrieves the remote URL for "origin".</li>
     * <li>Resolves the SHA1 ID for the {@code markedCommit} (or HEAD).</li>
     * <li>Resolves the SHA1 ID for the current HEAD (the built revision).</li>
     * <li>Constructs {@link Revision} objects for both the marked and built states.</li>
     * <li>Extracts the "humanish" name from the remote URL.</li>
     * </ol>
     *
     * @param workspace The root directory of the workspace on the agent.
     * @param channel   The virtual channel to the controller.
     * @return A {@link LocalGitInfo} object containing the gathered repository data.
     * @throws IOException          If an I/O error occurs, or if Git revisions cannot be resolved.
     * @throws InterruptedException If the operation is interrupted.
     */
    @Override
    public LocalGitInfo invoke(File workspace, VirtualChannel channel) throws IOException, InterruptedException {

        String url = git.getRemoteUrl(remote);

        String referenceHead = (markedCommit != null && !markedCommit.trim().isEmpty())
                           ? markedCommit
                           : "HEAD";

        ObjectId resolvedObjectId;
        try {
            resolvedObjectId = git.revParse(referenceHead);
        } catch (GitException e) {
            try {
                resolvedObjectId = git.revParse(remote + "/" + referenceHead);
            } catch (GitException e2) {
                throw new IOException("[GitCollect] Could not resolve revision '" +
                                       referenceHead + "' or '" + remote + "/" + referenceHead + "'", e);
            }
        }

        ObjectId topOnObjectId;
        try {
            topOnObjectId = git.revParse("HEAD");
        } catch (GitException e) {
            throw new IOException("[GitCollect] Could not resolve revision '" + referenceHead + "'", e);
        }

        LOGGER.log(Level.FINE, "referenceHead: " + referenceHead);

        try {
            Revision builtRevision = new Revision(topOnObjectId);
            Revision markedRevision = new Revision(resolvedObjectId);

            Collection<Branch> branches = new ArrayList<>();
            branches.add(new Branch(referenceHead, resolvedObjectId));
            builtRevision.setBranches(branches);

            if (markedCommit != null && !ObjectId.isId(markedCommit)) {
                branches = new ArrayList<>();
                branches.add(new Branch(markedCommit, resolvedObjectId));
                markedRevision.setBranches(branches);
            }

            URIish uri = new URIish(url);

            return new LocalGitInfo(uri.getHumanishName(), url, remote, builtRevision, markedRevision);
        } catch (URISyntaxException e) {
            throw new IOException("[GitCollect] Unable to find an uri name", e);
        }
    }
}
