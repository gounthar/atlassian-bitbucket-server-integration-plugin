package com.atlassian.bitbucket.jenkins.internal.status;

import com.atlassian.bitbucket.jenkins.internal.provider.GlobalLibrariesProvider;
import com.atlassian.bitbucket.jenkins.internal.scm.BitbucketPullRequestSCMHead;
import com.atlassian.bitbucket.jenkins.internal.scm.BitbucketSCM;
import com.atlassian.bitbucket.jenkins.internal.scm.BitbucketSCMRepository;
import com.atlassian.bitbucket.jenkins.internal.scm.BitbucketSCMRepositoryHelper;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.SCMListener;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.scm.SCMRevisionState;
import hudson.util.DescribableList;
import jenkins.branch.Branch;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.MultiBranchProject;
import jenkins.scm.api.SCMHead;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.workflow.libs.FolderLibraries;
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration;
import org.jenkinsci.plugins.workflow.libs.SCMRetriever;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import javax.annotation.CheckForNull;
import javax.inject.Inject;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static com.atlassian.bitbucket.jenkins.internal.scm.BitbucketPullRequestSourceBranch.PULL_REQUEST_SOURCE_COMMIT;
import static com.atlassian.bitbucket.jenkins.internal.scm.BitbucketTagSourceBranch.TAG_SOURCE_COMMIT;

@Extension
public class LocalSCMListener extends SCMListener {

    public static final String BRANCH_PREFIX = "refs/heads/";
    public static final String TAG_PREFIX = "refs/tags/";

    private BuildStatusPoster buildStatusPoster;
    private GlobalLibrariesProvider librariesProvider;
    private BitbucketSCMRepositoryHelper repositoryHelper;

    public LocalSCMListener() {
    }

    @Inject
    LocalSCMListener(BuildStatusPoster buildStatusPoster, GlobalLibrariesProvider librariesProvider,
                     BitbucketSCMRepositoryHelper repositoryHelper) {
        this.buildStatusPoster = buildStatusPoster;
        this.librariesProvider = librariesProvider;
        this.repositoryHelper = repositoryHelper;
    }

    @CheckForNull
    public GitSCM getUnderlyingGitSCM(SCM scm) {
        if (scm instanceof GitSCM) {
            // Already a git SCM
            return (GitSCM) scm;
        }
        if (scm instanceof BitbucketSCM) {
            BitbucketSCM bitbucketSCM = (BitbucketSCM) scm;
            if (bitbucketSCM.getServerId() != null) {
                return bitbucketSCM.getGitSCM();
            }
        }
        return null;
    }

    @Override
    public void onCheckout(Run<?, ?> build, SCM scm, FilePath workspace, TaskListener listener,
                           @CheckForNull File changelogFile,
                           @CheckForNull SCMRevisionState pollingBaseline) {
        // Check if the current SCM we are checking out is configured as a folder library SCM
        if (build.getParent().getParent() instanceof Folder && isFolderLib((Folder) build.getParent().getParent(), scm)) {
            return;
        }
        if (build.getParent().getParent() instanceof WorkflowMultiBranchProject &&
                projectHasFolderLibrary((WorkflowMultiBranchProject) build.getParent().getParent(), scm)) {
            return;
        }

        // Check if the current SCM we are checking out is configured as a global library SCM
        for (LibraryConfiguration cfg : librariesProvider.get().getLibraries()) {
            if (cfg.getRetriever() instanceof SCMRetriever) {
                SCMRetriever retriever = (SCMRetriever) cfg.getRetriever();
                if (retriever.getScm() instanceof BitbucketSCM && scm instanceof BitbucketSCM) {
                    BitbucketSCM libraryScm = (BitbucketSCM) retriever.getScm();
                    BitbucketSCM bitbucketScm = (BitbucketSCM) scm;
                    if (libraryScm.getId().equals(bitbucketScm.getId())) {
                        return;
                    }
                }
            }
        }

        BitbucketSCMRepository bitbucketSCMRepository = repositoryHelper.getRepository(build, scm);
        if (bitbucketSCMRepository == null) {
            return;
        }
        GitSCM underlyingScm = getUnderlyingGitSCM(scm);
        if (underlyingScm == null) {
            return;
        }
        Map<String, String> env = new HashMap<>();
        buildEnvironment(underlyingScm, build, env);

        String refName = getRefFromEnvironment(env, underlyingScm);
        BitbucketRevisionAction revisionAction =
                new BitbucketRevisionAction(bitbucketSCMRepository, refName, getCommitFromEnvironment(env));
        build.addAction(revisionAction);
        buildStatusPoster.postBuildStatus(revisionAction, build, listener);
    }

    private void buildEnvironment(GitSCM underlyingScm, Run<?, ?> build, Map<String, String> env) {
        underlyingScm.buildEnvironment(build, env);
        Job<?, ?> job = build.getParent();
        ItemGroup parent = job.getParent();
        if (parent instanceof MultiBranchProject) {
            BranchProjectFactory projectFactory = ((MultiBranchProject) parent).getProjectFactory();
            if (projectFactory.isProject(job)) {
                Branch branch = projectFactory.getBranch(job);
                SCMHead head = branch.getHead();
                if (head instanceof BitbucketPullRequestSCMHead) {
                    BitbucketPullRequestSCMHead prHead = (BitbucketPullRequestSCMHead) head;
                    env.put(GitSCM.GIT_BRANCH, prHead.getOriginName());
                }
            }
        }
    }

    private String getCommitFromEnvironment(Map<String, String> env) {
        // Pull requests may be built using a merge between the source and target branches which could result in a new
        // commit for the merge. This new merge commit will not exist in Bitbucket, so we need to use the pull request
        // source commit when posting the build status so that Bitbucket can correctly resolve the build to the correct
        // commit.
        String commit = env.get(PULL_REQUEST_SOURCE_COMMIT);
        if (commit == null) {
            commit = env.get(GitSCM.GIT_COMMIT);
        }

        return commit;
    }

    @CheckForNull
    private String getRefFromEnvironment(Map<String, String> env, GitSCM scm) {
        // If the pull request source commit is specified we do not need to specify the ref
        if (StringUtils.isNotBlank(env.get(PULL_REQUEST_SOURCE_COMMIT))) {
            return null;
        }

        String refId = StringUtils.stripToNull(env.get(GitSCM.GIT_BRANCH));
        if (refId == null) {
            return null;
        }

        if (StringUtils.isNotBlank(env.get(TAG_SOURCE_COMMIT))) {
            return env.get(TAG_SOURCE_COMMIT);
        }

        // Branches are in the form of the result of a git fetch, prepended with the repository name. The Git SCM
        // can strip the repo name (if it's found in the list of remote configs), and we append refs/heads afterwards.
        return BRANCH_PREFIX + scm.deriveLocalBranchName(refId);
    }

    private boolean isFolderLib(Folder folder, SCM scm) {
        if (isScmFolderLibrary(scm, folder.getProperties())) {
            return true;
        }
        if (folder.getParent() instanceof Folder) {
            // Recursively check parent folders for folder libraries
            return isFolderLib((Folder) folder.getParent(), scm);
        }
        return false;
    }

    private boolean isScmFolderLibrary(SCM scm,
                                       DescribableList<AbstractFolderProperty<?>, AbstractFolderPropertyDescriptor> properties) {
        for (Object folderItem : properties) {
            if (folderItem instanceof FolderLibraries) {
                FolderLibraries folderLibraries = (FolderLibraries) folderItem;
                for (LibraryConfiguration folderLib : folderLibraries.getLibraries()) {
                    if (folderLib.getRetriever() instanceof SCMRetriever) {
                        SCMRetriever retriever = (SCMRetriever) folderLib.getRetriever();
                        if (retriever.getScm() instanceof BitbucketSCM && scm instanceof BitbucketSCM) {
                            BitbucketSCM libraryScm = (BitbucketSCM) retriever.getScm();
                            BitbucketSCM bitbucketScm = (BitbucketSCM) scm;
                            if (libraryScm.getId().equals(bitbucketScm.getId())) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean projectHasFolderLibrary(WorkflowMultiBranchProject project, SCM scm) {
        if (isScmFolderLibrary(scm, project.getProperties())) {
            return true;
        }
        if (project.getParent() instanceof Folder) {
            return isFolderLib((Folder) project.getParent(), scm);
        }
        return false;
    }
}
