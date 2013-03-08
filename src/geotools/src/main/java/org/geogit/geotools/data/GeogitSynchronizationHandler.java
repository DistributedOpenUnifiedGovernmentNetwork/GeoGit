package org.geogit.geotools.data;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.geogit.api.GeoGIT;
import org.geogit.api.GeogitTransaction;
import org.geogit.api.Remote;
import org.geogit.api.plumbing.TransactionBegin;
import org.geogit.api.porcelain.CheckoutOp;
import org.geogit.api.porcelain.PullOp;
import org.geogit.api.porcelain.PushException;
import org.geogit.api.porcelain.PushOp;
import org.geogit.api.porcelain.RemoteResolve;

import com.google.common.base.Optional;
import com.google.common.base.Suppliers;

public class GeogitSynchronizationHandler {

    private ScheduledExecutorService executor = null;

    private static GeogitSynchronizationHandler instance = new GeogitSynchronizationHandler();

    private final Queue<Pair<GeoGIT, Optional<String>>> repositories;

    private GeogitSynchronizationHandler() {
        repositories = new ConcurrentLinkedQueue<Pair<GeoGIT, Optional<String>>>();

        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(new GeoGitSynchronizer(), 0, 1, TimeUnit.SECONDS);
    }

    public void setDirty(GeoGIT geogit, @Nullable String branch) {
        Optional<Remote> originRemote = geogit.command(RemoteResolve.class).setName("origin")
                .call();
        if (originRemote.isPresent()) {
            Pair<GeoGIT, Optional<String>> entry = new Pair<GeoGIT, Optional<String>>(geogit,
                    Optional.fromNullable(branch));
            if (!repositories.contains(entry)) {
                repositories.add(entry);
            }
        }
    }

    public static GeogitSynchronizationHandler get() {
        return instance;
    }

    private class GeoGitSynchronizer implements Runnable {
        public void run() {
            if (repositories.peek() != null) {
                Pair<GeoGIT, Optional<String>> repo = repositories.poll();
                GeogitTransaction geogitTx = repo.getFirst().command(TransactionBegin.class).call();
                // checkout the working branch
                try {
                    final String workingBranch = repo.getSecond().orNull();
                    if (workingBranch != null) {
                        geogitTx.command(CheckoutOp.class).setForce(true).setSource(workingBranch)
                                .call();
                    }

                    Optional<Remote> originRemote = geogitTx.command(RemoteResolve.class)
                            .setName("origin").call();
                    if (originRemote.isPresent()) {
                        PullOp pull = geogitTx.command(PullOp.class)
                                .setRemote(Suppliers.ofInstance(originRemote)).setRebase(true);
                        if (workingBranch != null) {
                            pull.addRefSpec(workingBranch);
                        } else {
                            pull.setAll(true);
                        }
                        pull.call();

                        PushOp push = geogitTx.command(PushOp.class).setRemote(
                                Suppliers.ofInstance(originRemote));
                        if (workingBranch != null) {
                            push.addRefSpec(workingBranch);
                        } else {
                            push.setAll(true);
                        }
                        try {
                            push.call();
                        } catch (PushException e) {
                            // Do nothing
                        }

                        geogitTx.commitSyncTransaction();
                    }
                } catch (RuntimeException e) {
                    // abort sync transaction, but do not stop trying to sync.
                    repositories.add(repo);
                    geogitTx.abort();
                }
            }
        }
    }

    private class Pair<F, S> {

        private final F first;

        private final S second;

        public Pair(F first, S second) {
            this.first = first;
            this.second = second;
        }

        public F getFirst() {
            return first;
        }

        public S getSecond() {
            return second;
        }

        @Override
        public int hashCode() {
            return first.hashCode() ^ second.hashCode();
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean equals(Object o) {
            if (o == null)
                return false;
            if (!(o instanceof Pair))
                return false;
            Pair<F, S> pair = (Pair<F, S>) o;
            return this.first.equals(pair.getFirst()) && this.second.equals(pair.getSecond());
        }

    }
}
