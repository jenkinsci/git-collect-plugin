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

@SuppressFBWarnings(value = "SE", justification = "GitClient implementation is Serializable")
public class GitScanner extends MasterToSlaveFileCallable<LocalGitInfo> {
    private static final long serialVersionUID = 1L;
    private final GitClient git;
    private final String targetStr;

    public static final Logger LOGGER = Logger.getLogger(GitScanner.class.getName());

    public GitScanner(GitClient git, String targetStr) {
        this.git = git;
        this.targetStr = targetStr;
    }

    @Override
    public LocalGitInfo invoke(File workspace, VirtualChannel channel) throws IOException, InterruptedException {

        String url = git.getRemoteUrl("origin");

        String targetHead = (targetStr != null && !targetStr.trim().isEmpty())
                           ? targetStr
                           : "HEAD";

        ObjectId resolvedObjectId;
        try {
            resolvedObjectId = git.revParse(targetHead);
        } catch (GitException e) {
            throw new IOException("[GitCollect] Could not resolve revision '" + targetHead + "'", e);
        }

        LOGGER.log(Level.FINE, "TargetHead: " + targetHead);

        try {
            Revision builtRevision = new Revision(resolvedObjectId);
            Revision markedRevision = new Revision(resolvedObjectId);

            Collection<Branch> branches = new ArrayList<>();
            branches.add(new Branch(targetHead, resolvedObjectId));
            builtRevision.setBranches(branches);

            if (targetStr != null && !ObjectId.isId(targetStr)) {
                branches = new ArrayList<>();
                branches.add(new Branch(targetStr, resolvedObjectId));
                markedRevision.setBranches(branches);
            }

            URIish uri = new URIish(url);

            return new LocalGitInfo(uri.getHumanishName(), url, builtRevision, markedRevision);
        } catch (URISyntaxException e) {
            throw new IOException("[GitCollect] Unable to find an uri name", e);
        }
    }
}
