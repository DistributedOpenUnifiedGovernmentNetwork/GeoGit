/* Copyright (c) 2011 TOPP - www.openplans.org. All rights reserved.
 * This code is licensed under the LGPL 2.1 license, available at the root
 * application directory.
 */

package org.geogit.storage;

import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.geogit.api.CommandLocator;
import org.geogit.api.GeogitTransaction;
import org.geogit.api.Ref;
import org.geogit.api.plumbing.TransactionBegin;
import org.geogit.api.plumbing.TransactionEnd;
import org.geogit.repository.Index;
import org.geogit.repository.WorkingTree;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

/**
 * A {@link RefDatabase} decorator for a specific {@link GeogitTransaction transaction}.
 * <p>
 * This decorator creates a transaction specific namespace under the
 * {@code transactions/<transaction id>} path, and maps all query and storage methods to that
 * namespace.
 * <p>
 * This is so that every command created through the {@link GeogitTransaction transaction} used as a
 * {@link CommandLocator}, as well as the transaction specific {@link Index} and {@link WorkingTree}
 * , are given this instance of {@code RefDatabase} and can do its work without ever noticing its
 * "running inside a transaction". For the command nothing changes.
 * <p>
 * {@link TransactionRefDatabase#create() create()} shall be called before this decorator gets used
 * in order for the transaction refs namespace to be created and all original references copied in
 * there, and {@link TransactionRefDatabase#close() close()} for the transaction refs namespace to
 * be deleted.
 * 
 * @see GeogitTransaction
 * @see TransactionBegin
 * @see TransactionEnd
 */
public class TransactionRefDatabase implements RefDatabase {

    private static final String TRANSACTIONS_PREFIX = "transactions/";

    private RefDatabase refDb;

    private final String txPrefix;

    private final String txOrigPrefix;

    public TransactionRefDatabase(final RefDatabase refDb, final UUID transactionId) {
        this.refDb = refDb;
        this.txPrefix = TRANSACTIONS_PREFIX + transactionId.toString() + "/";
        this.txOrigPrefix = txPrefix + "orig/";
    }

    @Override
    public void lock() throws TimeoutException {
        refDb.lock();
    }

    @Override
    public void unlock() {
        refDb.unlock();
    }

    @Override
    public void create() {
        refDb.create();

        // copy HEADS
        String headValue = readRef(Ref.HEAD);
        if (headValue != null) {
            insertRef(toInternal(Ref.HEAD), headValue);
        }

        String workHeadValue = readRef(Ref.WORK_HEAD);
        if (workHeadValue != null) {
            insertRef(toInternal(Ref.WORK_HEAD), workHeadValue);
        }

        String stageHeadValue = readRef(Ref.STAGE_HEAD);
        if (stageHeadValue != null) {
            insertRef(toInternal(Ref.STAGE_HEAD), stageHeadValue);
        }

        Map<String, String> origRefs = refDb.getAll(Ref.REFS_PREFIX);
        Map<String, String> thisTxRefs = toOrigInternal(origRefs);

        for (Entry<String, String> entry : thisTxRefs.entrySet()) {
            insertRef(entry.getKey(), entry.getValue());
        }
    }

    private String readRef(String name) {
        String value = null;
        try {
            value = refDb.getRef(name);
        } catch (IllegalArgumentException e) {
            value = refDb.getSymRef(name);
        }
        return value;
    }

    private void insertRef(String name, String value) {
        if (value.contains("/")) {
            refDb.putSymRef(name, value);
        } else {
            refDb.putRef(name, value);
        }
    }

    /**
     * Releases all the references for this transaction, but does not close the original
     * {@link RefDatabase}
     */
    @Override
    public void close() {
        refDb.removeAll(this.txPrefix);
    }

    /**
     * Gets the requested ref value from {@code transactions/<tx id>/<name>}
     */
    @Override
    public String getRef(final String name) {
        String internalName = toInternal(name);
        String value = refDb.getRef(internalName);
        if (value == null) {
            internalName = toOrigInternal(name);
            value = refDb.getRef(internalName);
        }
        return value;
    }

    @Override
    public String getSymRef(final String name) {
        String internalName = toInternal(name);
        String value = refDb.getSymRef(internalName);
        if (value == null) {
            internalName = toOrigInternal(name);
            value = refDb.getSymRef(internalName);
        }
        return value;
    }

    @Override
    public void putRef(final String refName, final String refValue) {
        String internalName = toInternal(refName);
        refDb.putRef(internalName, refValue);
    }

    @Override
    public void putSymRef(final String name, final String val) {
        String internalName = toInternal(name);
        refDb.putSymRef(internalName, val);
    }

    @Override
    public String remove(final String refName) {
        return refDb.remove(toInternal(refName));
    }

    @Override
    public Map<String, String> getAll() {
        return getAll("");
    }

    @Override
    public Map<String, String> getAll(final String prefix) {
        String transactionPrefix = this.txOrigPrefix + prefix;
        Map<String, String> originals = refDb.getAll(transactionPrefix);
        Map<String, String> composite = Maps.newHashMap(toExternal(originals));

        transactionPrefix = this.txPrefix + prefix;
        Map<String, String> changed = refDb.getAll(transactionPrefix);
        changed = toExternal(changed);

        // Overwrite originals
        for (Entry<String, String> entry : changed.entrySet()) {
            composite.put(entry.getKey(), entry.getValue());
        }

        return composite;

    }

    @Override
    public Map<String, String> removeAll(String namespace) {
        final String txMappedNamespace = toInternal(namespace);
        Map<String, String> removed = refDb.removeAll(txMappedNamespace);
        Map<String, String> external = toExternal(removed);
        return external;
    }

    private Map<String, String> toExternal(final Map<String, String> transactionEntries) {

        Map<String, String> transformed = Maps.newHashMap();
        for (Entry<String, String> entry : transactionEntries.entrySet()) {

            String txName = entry.getKey();
            String txValue = entry.getValue();

            String transformedName = toExternal(txName);
            String transformedValue = toExternalValue(txValue);
            transformed.put(transformedName, transformedValue);
        }
        return ImmutableMap.copyOf(transformed);
    }

    private String toInternal(String name) {
        return txPrefix + name;
    }

    private String toExternal(String name) {
        if (name.startsWith(this.txPrefix)) {
            return name.substring(this.txPrefix.length());
        } else if (name.startsWith(this.txOrigPrefix)) {
            return name.substring(this.txOrigPrefix.length());
        }
        return name;
    }

    private String toOrigInternal(String name) {
        return txOrigPrefix + name;
    }

    private Map<String, String> toOrigInternal(final Map<String, String> orig) {

        Map<String, String> transformed = Maps.newHashMap();
        for (Entry<String, String> entry : orig.entrySet()) {

            String origName = entry.getKey();
            String origValue = entry.getValue();

            String transformedName = toOrigInternal(origName);
            String transformedValue = origValue;
            transformed.put(transformedName, transformedValue);
        }
        return ImmutableMap.copyOf(transformed);
    }

    private String toExternalValue(String origValue) {
        String txValue = origValue;
        boolean isSymRef = origValue.startsWith("ref: ");
        if (isSymRef) {
            String val = origValue.substring("ref: ".length());
            if (val.startsWith(this.txPrefix)) {
                val = val.substring(this.txPrefix.length());
            }
            txValue = "ref: " + val;
        }
        return txValue;
    }

}
