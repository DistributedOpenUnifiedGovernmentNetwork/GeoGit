/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */

package org.geogit.remote;

import static com.google.common.base.Preconditions.checkState;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.geogit.api.CommitBuilder;
import org.geogit.api.IniRepositoryFilter;
import org.geogit.api.ObjectId;
import org.geogit.api.Ref;
import org.geogit.api.RepositoryFilter;
import org.geogit.api.RevCommit;
import org.geogit.api.RevObject;
import org.geogit.api.RevObject.TYPE;
import org.geogit.api.RevTree;
import org.geogit.api.plumbing.FindCommonAncestor;
import org.geogit.api.plumbing.ResolveGeogitDir;
import org.geogit.api.plumbing.ResolveTreeish;
import org.geogit.api.plumbing.WriteTree;
import org.geogit.api.plumbing.diff.DiffEntry;
import org.geogit.api.porcelain.ConfigOp;
import org.geogit.api.porcelain.ConfigOp.ConfigAction;
import org.geogit.api.porcelain.SynchronizationException;
import org.geogit.api.porcelain.SynchronizationException.StatusCode;
import org.geogit.repository.Repository;
import org.geogit.storage.GraphDatabase;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

/**
 * Abstract base implementation for mapped (sparse) clone.
 */
public abstract class AbstractMappedRemoteRepo implements IRemoteRepo {

    public static String PLACEHOLDER_COMMIT_MESSAGE = "Placeholder Sparse Commit";

    protected Repository localRepository;

    protected RepositoryFilter filter;

    /**
     * Constructs a new {@code AbstractMappedRemoteRepo} with the provided reference repository.
     * 
     * @param localRepository the local repository.
     */
    public AbstractMappedRemoteRepo(Repository localRepository) {
        this.localRepository = localRepository;
        Optional<Map<String, String>> filterResult = localRepository.command(ConfigOp.class)
                .setAction(ConfigAction.CONFIG_GET).setName("sparse.filter").call();
        Preconditions.checkState(filterResult.isPresent(), "No filter found for sparse clone.");
        String filterFile = filterResult.get().get("sparse.filter");
        Preconditions.checkState(filterFile != null, "No filter found for sparse clone.");
        try {
            Optional<URL> envHome = localRepository.command(ResolveGeogitDir.class).call();
            checkState(envHome.isPresent(), "Not inside a geogit directory");
            final URL envLocation = envHome.get();
            if (!"file".equals(envLocation.getProtocol())) {
                throw new UnsupportedOperationException(
                        "Sparse clone works only against file system repositories. "
                                + "Repository location: " + envLocation.toExternalForm());
            }
            File repoDir;
            try {
                repoDir = new File(envLocation.toURI());
            } catch (URISyntaxException e) {
                throw Throwables.propagate(e);
            }
            File newFilterFile = new File(repoDir, filterFile);
            filter = new IniRepositoryFilter(newFilterFile.getAbsolutePath());
        } catch (FileNotFoundException e) {
            Throwables.propagate(e);
        }
    }

    /**
     * CommitTraverser for gathering all of the commits that I need to fetch.
     */
    protected class FetchCommitGatherer extends CommitTraverser {

        RepositoryWrapper source;

        Repository destination;

        public FetchCommitGatherer(RepositoryWrapper source, Repository destination) {
            this.source = source;
            this.destination = destination;
        }

        @Override
        protected Evaluation evaluate(CommitNode commitNode) {
            if (destination.graphDatabase().exists(commitNode.getObjectId())) {
                return Evaluation.EXCLUDE_AND_PRUNE;
            }
            return Evaluation.INCLUDE_AND_CONTINUE;
        }

        @Override
        protected ImmutableList<ObjectId> getParentsInternal(ObjectId commitId) {
            return source.getParents(commitId);
        }

        @Override
        protected boolean existsInDestination(ObjectId commitId) {
            return destination.graphDatabase().exists(commitId);
        }

    };

    /**
     * CommitTraverser for gathering all of the commits that I need to push.
     */
    protected class PushCommitGatherer extends CommitTraverser {

        Repository source;

        public PushCommitGatherer(Repository source) {
            this.source = source;
        }

        @Override
        protected Evaluation evaluate(CommitNode commitNode) {
            if (!source.graphDatabase().getMapping(commitNode.getObjectId()).equals(ObjectId.NULL)) {
                return Evaluation.EXCLUDE_AND_PRUNE;
            }
            return Evaluation.INCLUDE_AND_CONTINUE;
        }

        @Override
        protected ImmutableList<ObjectId> getParentsInternal(ObjectId commitId) {
            return source.graphDatabase().getParents(commitId);
        }

        @Override
        protected boolean existsInDestination(ObjectId commitId) {
            // If the commit has not been mapped, it hasn't been pushed to the remote yet
            return !source.graphDatabase().getMapping(commitId).equals(ObjectId.NULL);
        }

    };

    /**
     * @return the {@link RepositoryWrapper} for this remote
     */
    protected abstract RepositoryWrapper getRemoteWrapper();

    /**
     * Fetch all new objects from the specified {@link Ref} from the remote.
     * 
     * @param ref the remote ref that points to new commit data
     * @param fetchLimit the maximum depth to fetch, note, a sparse clone cannot be a shallow clone
     */
    public void fetchNewData(Ref ref, Optional<Integer> fetchLimit) {
        Preconditions.checkState(!fetchLimit.isPresent(), "A sparse clone cannot be shallow.");
        FetchCommitGatherer gatherer = new FetchCommitGatherer(getRemoteWrapper(), localRepository);

        try {
            gatherer.traverse(ref.getObjectId());
            Stack<ObjectId> needed = gatherer.commits;
            while (!needed.empty()) {
                ObjectId commitId = needed.pop();
                // If the last commit is empty, add it anyways to preserve parentage of new commits.
                boolean allowEmpty = needed.isEmpty();
                fetchSparseCommit(commitId, allowEmpty);
            }

        } catch (Exception e) {
            Throwables.propagate(e);
        } finally {
        }
    }

    /**
     * This function takes all of the changes introduced by the specified commit and filters them
     * based on the repository filter. It then uses the filtered results to construct a new commit
     * that is the descendant of commits that the original's parents are mapped to.
     * 
     * @param commitId the commit id of the original, non-sparse commit
     * @param allowEmpty allow the function to create an empty sparse commit
     */
    protected void fetchSparseCommit(ObjectId commitId, boolean allowEmpty) {

        Optional<RevObject> object = getObject(commitId);
        if (object.isPresent() && object.get().getType().equals(TYPE.COMMIT)) {
            RevCommit commit = (RevCommit) object.get();

            FilteredDiffIterator changes = getFilteredChanges(commit);

            localRepository.graphDatabase().put(commit.getId(), commit.getParentIds());

            RevTree rootTree = RevTree.EMPTY;

            if (commit.getParentIds().size() > 0) {
                // Map this commit to the last "sparse" commit in my ancestry
                ObjectId mappedCommit = localRepository.graphDatabase().getMapping(
                        commit.getParentIds().get(0));
                localRepository.graphDatabase().map(commit.getId(), mappedCommit);
                Optional<ObjectId> treeId = localRepository.command(ResolveTreeish.class)
                        .setTreeish(mappedCommit).call();
                if (treeId.isPresent() && !treeId.get().equals(ObjectId.NULL)) {
                    rootTree = localRepository.getTree(treeId.get());
                }

            } else {
                localRepository.graphDatabase().map(commit.getId(), ObjectId.NULL);
            }

            if (changes.hasNext()) {
                // Create new commit
                ObjectId newTreeId = localRepository.command(WriteTree.class)
                        .setOldRoot(Suppliers.ofInstance(rootTree))
                        .setDiffSupplier(Suppliers.ofInstance((Iterator<DiffEntry>) changes))
                        .call();

                CommitBuilder builder = new CommitBuilder(commit);
                List<ObjectId> newParents = new LinkedList<ObjectId>();
                for (ObjectId parentCommitId : commit.getParentIds()) {
                    newParents.add(localRepository.graphDatabase().getMapping(parentCommitId));
                }
                builder.setParentIds(newParents);
                builder.setTreeId(newTreeId);

                RevCommit mapped = builder.build();
                localRepository.objectDatabase().put(mapped);

                if (changes.wasFiltered()) {
                    localRepository.graphDatabase().setProperty(mapped.getId(),
                            GraphDatabase.SPARSE_FLAG, "true");
                }

                localRepository.graphDatabase().map(mapped.getId(), commit.getId());
                // Replace the old mapping with the new commit Id.
                localRepository.graphDatabase().map(commit.getId(), mapped.getId());
            } else if (allowEmpty) {
                CommitBuilder builder = new CommitBuilder(commit);
                List<ObjectId> newParents = new LinkedList<ObjectId>();
                for (ObjectId parentCommitId : commit.getParentIds()) {
                    newParents.add(localRepository.graphDatabase().getMapping(parentCommitId));
                }
                builder.setParentIds(newParents);
                builder.setTreeId(rootTree.getId());
                builder.setMessage(PLACEHOLDER_COMMIT_MESSAGE);

                RevCommit mapped = builder.build();
                localRepository.objectDatabase().put(mapped);

                localRepository.graphDatabase().setProperty(mapped.getId(),
                        GraphDatabase.SPARSE_FLAG, "true");

                localRepository.graphDatabase().map(mapped.getId(), commit.getId());
                // Replace the old mapping with the new commit Id.
                localRepository.graphDatabase().map(commit.getId(), mapped.getId());
            } else {
                // Mark the mapped commit as sparse, since it wont have these changes
                localRepository.graphDatabase().setProperty(
                        localRepository.graphDatabase().getMapping(commit.getId()),
                        GraphDatabase.SPARSE_FLAG, "true");
            }
        }
    }

    /**
     * Retrieves an object with the specified id from the remote.
     * 
     * @param objectId the object to get
     * @return the fetched object
     */
    protected abstract Optional<RevObject> getObject(ObjectId objectId);

    /**
     * Gets all of the changes from the target commit that should be applied to the sparse clone.
     * 
     * @param commit the commit to get changes from
     * @return an iterator for changes that match the repository filter
     */
    protected abstract FilteredDiffIterator getFilteredChanges(RevCommit commit);

    /**
     * Push all new objects from the specified {@link Ref} to the remote.
     * 
     * @param ref the local ref that points to new commit data
     */
    @Override
    public void pushNewData(Ref ref) throws SynchronizationException {
        pushNewData(ref, ref.getName());
    }

    /**
     * Push all new objects from the specified {@link Ref} to the given refspec.
     * 
     * @param ref the local ref that points to new commit data
     * @param refspec the refspec to push to
     */
    @Override
    public void pushNewData(Ref ref, String refspec) throws SynchronizationException {
        Optional<Ref> remoteRef = getRemoteRef(refspec);
        checkPush(ref, remoteRef);
        beginPush();

        PushCommitGatherer gatherer = new PushCommitGatherer(localRepository);

        try {
            gatherer.traverse(ref.getObjectId());
            Stack<ObjectId> needed = gatherer.commits;

            while (!needed.isEmpty()) {
                ObjectId commitToPush = needed.pop();

                pushSparseCommit(commitToPush);
            }

            ObjectId newCommitId = localRepository.graphDatabase().getMapping(ref.getObjectId());

            ObjectId originalRemoteRefValue = ObjectId.NULL;
            if (remoteRef.isPresent()) {
                originalRemoteRefValue = remoteRef.get().getObjectId();
            }

            endPush(refspec, newCommitId, originalRemoteRefValue.toString());

        } catch (Exception e) {
            Throwables.propagate(e);
        } finally {
        }
    }

    /**
     * Gets the remote ref that matches the provided ref spec.
     * 
     * @param refspec the refspec to parse
     * @return the matching {@link Ref} or {@link Optional#absent()} if the ref could not be found
     */
    protected abstract Optional<Ref> getRemoteRef(String refspec);

    /**
     * Perform pre-push actions.
     */
    protected void beginPush() {
        // do nothing
    }

    /**
     * Perform post-push actions, this includes verification that the remote wasn't changed while we
     * were pushing.
     * 
     * @param refspec the refspec that we are pushing to
     * @param newCommitId the new commit id
     * @param originalRefValue the original value of the ref before pushing
     */
    protected void endPush(String refspec, ObjectId newCommitId, String originalRefValue) {
        updateRemoteRef(refspec, newCommitId, false);
    }

    /**
     * Updates the remote ref that matches the given refspec.
     * 
     * @param refspec the ref to update
     * @param commitId the new value of the ref
     * @param delete if true, the remote ref will be deleted
     * @return the updated ref
     */
    protected abstract Ref updateRemoteRef(String refspec, ObjectId commitId, boolean delete);

    /**
     * Pushes a sparse commit to a remote repository and updates all mappings.
     * 
     * @param commitId the commit to push
     */
    protected abstract void pushSparseCommit(ObjectId commitId);

    /**
     * Determine if it is safe to push to the remote repository.
     * 
     * @param ref the ref to push
     * @param remoteRef the ref to push to
     * @throws SynchronizationException
     */
    protected void checkPush(Ref ref, Optional<Ref> remoteRef) throws SynchronizationException {
        if (remoteRef.isPresent()) {
            ObjectId mappedId = localRepository.graphDatabase().getMapping(
                    remoteRef.get().getObjectId());
            if (mappedId.equals(ref.getObjectId())) {
                // The branches are equal, no need to push.
                throw new SynchronizationException(StatusCode.NOTHING_TO_PUSH);
            } else if (localRepository.blobExists(mappedId)) {
                Optional<ObjectId> ancestor = localRepository.command(FindCommonAncestor.class)
                        .setLeftId(mappedId).setRightId(ref.getObjectId()).call();
                if (!ancestor.isPresent()) {
                    // There is no common ancestor, a push will overwrite history
                    throw new SynchronizationException(StatusCode.REMOTE_HAS_CHANGES);
                } else if (ancestor.get().equals(ref.getObjectId())) {
                    // My last commit is the common ancestor, the remote already has my data.
                    throw new SynchronizationException(StatusCode.NOTHING_TO_PUSH);
                } else if (!ancestor.get().equals(mappedId)) {
                    // The remote branch's latest commit is not my ancestor, a push will cause a
                    // loss of history.
                    throw new SynchronizationException(StatusCode.REMOTE_HAS_CHANGES);
                }
            } else {
                // The remote has data that I do not, a push will cause this data to be lost.
                throw new SynchronizationException(StatusCode.REMOTE_HAS_CHANGES);
            }
        }
    }
}
