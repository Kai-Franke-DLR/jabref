package org.jabref.logic.shared;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.jabref.gui.Globals;
import org.jabref.logic.citationkeypattern.GlobalCitationKeyPattern;
import org.jabref.logic.exporter.BibDatabaseWriter;
import org.jabref.logic.exporter.MetaDataSerializer;
import org.jabref.logic.importer.ParseException;
import org.jabref.logic.importer.util.MetaDataParser;
import org.jabref.logic.shared.event.ConnectionLostEvent;
import org.jabref.logic.shared.event.SharedEntriesNotPresentEvent;
import org.jabref.logic.shared.event.UpdateRefusedEvent;
import org.jabref.logic.shared.exception.OfflineLockException;
import org.jabref.model.database.BibDatabase;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.database.event.EntriesAddedEvent;
import org.jabref.model.database.event.EntriesRemovedEvent;
import org.jabref.model.database.event.EntriesSavedEvent;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.event.EntriesEvent;
import org.jabref.model.entry.event.EntriesEventSource;
import org.jabref.model.entry.event.FieldChangedEvent;
import org.jabref.model.metadata.MetaData;
import org.jabref.model.metadata.event.MetaDataChangedEvent;
import org.jabref.model.util.FileUpdateMonitor;
import org.jabref.preferences.PreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Synchronizes the shared or local databases with their opposite side. Local changes are pushed by {@link EntriesEvent}
 * using Google's Guava EventBus.
 */
public class DBMSSynchronizer implements DatabaseSynchronizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DBMSSynchronizer.class);

    private DBMSProcessor dbmsProcessor;
    private String dbName;
    private final BibDatabaseContext bibDatabaseContext;
    private MetaData metaData;
    private final BibDatabase bibDatabase;
    private final EventBus eventBus;
    private Connection currentConnection;
    private final Character keywordSeparator;
    private final GlobalCitationKeyPattern globalCiteKeyPattern;
    private final FileUpdateMonitor fileMonitor;
    private Optional<BibEntry> lastEntryChanged;

    private final PreferencesService preferencesService;

    public DBMSSynchronizer(BibDatabaseContext bibDatabaseContext, Character keywordSeparator,
                            GlobalCitationKeyPattern globalCiteKeyPattern, FileUpdateMonitor fileMonitor) {
        this.bibDatabaseContext = Objects.requireNonNull(bibDatabaseContext);
        this.bibDatabase = bibDatabaseContext.getDatabase();
        this.metaData = bibDatabaseContext.getMetaData();
        this.fileMonitor = fileMonitor;
        this.eventBus = new EventBus();
        this.keywordSeparator = keywordSeparator;
        this.globalCiteKeyPattern = Objects.requireNonNull(globalCiteKeyPattern);
        this.lastEntryChanged = Optional.empty();
        this.preferencesService = Globals.prefs;
    }

    /**
     * Listening method. Inserts a new {@link BibEntry} into shared database.
     *
     * @param event {@link EntriesAddedEvent} object
     */
    @Subscribe
    public void listen(EntriesAddedEvent event) {
        // While synchronizing the local database (see synchronizeLocalDatabase() below), some EntriesEvents may be posted.
        // In this case DBSynchronizer should not try to insert the bibEntry entry again (but it would not harm).
        if (isEventSourceAccepted(event) && checkCurrentConnection()) {
            synchronizeLocalMetaData();
            pullWithLastEntry();
            synchronizeLocalDatabase();
            dbmsProcessor.insertEntries(event.getBibEntries());
            // Reset last changed entry because it just has already been synchronized -> Why necessary?
            lastEntryChanged = Optional.empty();
        }
    }

    /**
     * Listening method. Updates an existing shared {@link BibEntry}.
     *
     * @param event {@link FieldChangedEvent} object
     */
    @Subscribe
    public void listen(FieldChangedEvent event) {
        BibEntry bibEntry = event.getBibEntry();
        boolean shouldAutosave = preferencesService.shouldAutosaveShared();
        // While synchronizing the local database (see synchronizeLocalDatabase() below), some EntriesEvents may be posted.
        // In this case DBSynchronizer should not try to update the bibEntry entry again (but it would not harm).
        if (isPresentLocalBibEntry(bibEntry) && isEventSourceAccepted(event) && checkCurrentConnection() && !event.isFilteredOut() && shouldAutosave) {
            long[] times = new long[5];
            String[] names = { "synchronizeLocalMetaData", "pullWithLastEntry", "synchronizeSharedEntry", "synchronizeLocalDatabase" };
            times [0] = System.nanoTime();
            synchronizeLocalMetaData();
            times [1] = System.nanoTime();
            pullWithLastEntry(bibEntry);
            times [2] = System.nanoTime();
            synchronizeSharedEntry(bibEntry);
            times [3] = System.nanoTime();
            synchronizeLocalDatabase(); // Pull changes for the case that there were some
            times [4] = System.nanoTime();

            for (int i = 1; i < times.length; i++) {
                LOGGER.info("Time (ms) for {}: {}", names[i-1], (times[i] - times[i-1])/1000000);
            }
        } else {
            // Set new BibEntry that has been changed last
            lastEntryChanged = Optional.of(bibEntry);
        }
    }

    /**
     * Listening method. Deletes the given list of {@link BibEntry} from shared database.
     *
     * @param event {@link EntriesRemovedEvent} object
     */

    @Subscribe
    public void listen(EntriesRemovedEvent event) {
        // While synchronizing the local database (see synchronizeLocalDatabase() below), some EntriesEvents may be posted.
        // In this case DBSynchronizer should not try to delete the bibEntry entry again (but it would not harm).
        if (isEventSourceAccepted(event) && checkCurrentConnection()) {
            synchronizeLocalMetaData();
            pullWithLastEntry();
            dbmsProcessor.removeEntries(event.getBibEntries());
            synchronizeLocalDatabase();
        }
    }

    /**
     * Listening method. Deletes the given list of {@link BibEntry} from shared database.
     *
     * @param event {@link EntriesRemovedEvent} object
     */

    @Subscribe
    public void listen(EntriesSavedEvent event) {
        List<BibEntry> bibEntries = event.getBibEntries();

        for (BibEntry bibEntry : bibEntries) {
            // While synchronizing the local database (see synchronizeLocalDatabase() below), some EntriesEvents may be posted.
            // In this case DBSynchronizer should not try to update the bibEntry entry again (but it would not harm).
            if (isPresentLocalBibEntry(bibEntry) && isEventSourceAccepted(event) && checkCurrentConnection()) {
                synchronizeLocalMetaData();
                pullWithLastEntry(bibEntry);
                synchronizeSharedEntry(bibEntry);
                synchronizeLocalDatabase(); // Pull changes for the case that there were some
            } else {
                // Set new BibEntry that has been changed last
                lastEntryChanged = Optional.of(bibEntry);
            }
        }
    }

    /**
     * Listening method. Synchronizes the shared {@link MetaData} and applies them locally.
     *
     * @param event
     */
    @Subscribe
    public void listen(MetaDataChangedEvent event) {
        if (checkCurrentConnection()) {
            synchronizeSharedMetaData(event.getMetaData(), globalCiteKeyPattern);
            synchronizeLocalDatabase();
            applyMetaData();
            dbmsProcessor.notifyClients();
        }
    }

    /**
     * Sets the table structure of shared database if needed and pulls all shared entries to the new local database.
     *
     * @throws DatabaseNotSupportedException if the version of shared database does not match the version of current
     *                                       shared database support ({@link DBMSProcessor}).
     */
    public void initializeDatabases() throws DatabaseNotSupportedException {
        try {
            if (!dbmsProcessor.checkBaseIntegrity()) {
                LOGGER.info("Integrity check failed. Fixing...");

                // This check should only be performed once on initial database setup.
                if (dbmsProcessor.databaseIsAtMostJabRef35()) {
                    throw new DatabaseNotSupportedException();
                }

                // Calling dbmsProcessor.setupSharedDatabase() lets dbmsProcessor.checkBaseIntegrity() be true.
                dbmsProcessor.setupSharedDatabase();
            }
        } catch (SQLException e) {
            LOGGER.error("Could not check intergrity", e);
            throw new IllegalStateException(e);
        }

        dbmsProcessor.startNotificationListener(this);
        synchronizeLocalMetaData();
        synchronizeLocalDatabase();
    }

    /**
     * Synchronizes the local database with shared one. Possible update types are: removal, update, or insert of a
     * {@link BibEntry}.
     */
    @Override
    public void synchronizeLocalDatabase() {
        if (!checkCurrentConnection()) {
            return;
        }

        List<BibEntry> localEntries = bibDatabase.getEntries();
        Map<Integer, Integer> idVersionMap = dbmsProcessor.getSharedIDVersionMapping();

        // remove old entries locally
        removeNotSharedEntries(localEntries, idVersionMap.keySet());
        List<Integer> entriesToInsertIntoLocalDatabase = new ArrayList<>();
        // compare versions and update local entry if needed
        for (Map.Entry<Integer, Integer> idVersionEntry : idVersionMap.entrySet()) {
            boolean remoteEntryMatchingOneLocalEntryFound = false;
            for (BibEntry localEntry : localEntries) {
                if (idVersionEntry.getKey().equals(localEntry.getSharedBibEntryData().getSharedID())) {
                    remoteEntryMatchingOneLocalEntryFound = true;
                    if (idVersionEntry.getValue() > localEntry.getSharedBibEntryData().getVersion()) {
                        Optional<BibEntry> sharedEntry = dbmsProcessor.getSharedEntry(idVersionEntry.getKey());
                        if (sharedEntry.isPresent()) {
                            // update fields
                            localEntry.setType(sharedEntry.get().getType(), EntriesEventSource.SHARED);
                            localEntry.getSharedBibEntryData()
                                      .setVersion(sharedEntry.get().getSharedBibEntryData().getVersion());
                            sharedEntry.get().getFieldMap().forEach(
                                    // copy remote values to local entry
                                    (field, value) -> localEntry.setField(field, value, EntriesEventSource.SHARED)
                            );

                            // locally remove not existing fields
                            localEntry.getFields().stream()
                                      .filter(field -> !sharedEntry.get().hasField(field))
                                      .forEach(
                                              field -> localEntry.clearField(field, EntriesEventSource.SHARED)
                                      );
                        }
                    }
                }
            }
            if (!remoteEntryMatchingOneLocalEntryFound) {
                entriesToInsertIntoLocalDatabase.add(idVersionEntry.getKey());
            }
        }

        if (!entriesToInsertIntoLocalDatabase.isEmpty()) {
            // in case entries should be added into the local database, insert them
            bibDatabase.insertEntries(dbmsProcessor.getSharedEntries(entriesToInsertIntoLocalDatabase), EntriesEventSource.SHARED);
        }
    }

    /**
     * Removes all local entries which are not present on shared database.
     *
     * @param localEntries List of {@link BibEntry} the entries should be removed from
     * @param sharedIDs    Set of all IDs which are present on shared database
     */
    private void removeNotSharedEntries(List<BibEntry> localEntries, Set<Integer> sharedIDs) {
        List<BibEntry> entriesToRemove =
                localEntries.stream()
                            .filter(localEntry -> !sharedIDs.contains(localEntry.getSharedBibEntryData().getSharedID()))
                            .collect(Collectors.toList());
        if (!entriesToRemove.isEmpty()) {
            eventBus.post(new SharedEntriesNotPresentEvent(entriesToRemove));
            // remove all non-shared entries without triggering listeners
            bibDatabase.removeEntries(entriesToRemove, EntriesEventSource.SHARED);
        }
    }

    /**
     * Synchronizes the shared {@link BibEntry} with the local one.
     */
    @Override
    public void synchronizeSharedEntry(BibEntry bibEntry) {
        if (!checkCurrentConnection()) {
            return;
        }
        BibDatabaseWriter.applySaveActions(bibEntry, metaData); // perform possibly existing save actions

        try {
            dbmsProcessor.updateEntry(bibEntry);
        } catch (OfflineLockException exception) {
            eventBus.post(new UpdateRefusedEvent(bibDatabaseContext, exception.getLocalBibEntry(), exception.getSharedBibEntry()));
        } catch (SQLException e) {
            LOGGER.error("SQL Error", e);
        }
        /*
        Runnable runnable = () -> {
            try {
                dbmsProcessor.updateEntry(bibEntry);
            } catch (OfflineLockException exception) {
                //eventBus.post(new UpdateRefusedEvent(bibDatabaseContext, exception.getLocalBibEntry(), exception.getSharedBibEntry()));
                LOGGER.error("OfflineLockException", exception);
            } catch (SQLException e) {
                LOGGER.error("SQL Error", e);
            }
        };
        Thread thread = new Thread(runnable);
        thread.start();
        */
    }

    /**
     * Synchronizes all meta data locally.
     */
    public void synchronizeLocalMetaData() {
        if (!checkCurrentConnection()) {
            return;
        }

        try {
            metaData.setEventPropagation(false);
            MetaDataParser parser = new MetaDataParser(fileMonitor);
            parser.parse(metaData, dbmsProcessor.getSharedMetaData(), keywordSeparator);
            metaData.setEventPropagation(true);
        } catch (ParseException e) {
            LOGGER.error("Parse error", e);
        }
    }

    /**
     * Synchronizes all shared meta data.
     */
    private void synchronizeSharedMetaData(MetaData data, GlobalCitationKeyPattern globalCiteKeyPattern) {
        if (!checkCurrentConnection()) {
            return;
        }
        try {
            dbmsProcessor.setSharedMetaData(MetaDataSerializer.getSerializedStringMap(data, globalCiteKeyPattern));
        } catch (SQLException e) {
            LOGGER.error("SQL Error: ", e);
        }
    }

    /**
     * Applies the {@link MetaData} on all local and shared BibEntries.
     */
    public void applyMetaData() {
        if (!checkCurrentConnection()) {
            return;
        }
        for (BibEntry bibEntry : bibDatabase.getEntries()) {
            try {
                // synchronize only if changes were present
                if (!BibDatabaseWriter.applySaveActions(bibEntry, metaData).isEmpty()) {
                    dbmsProcessor.updateEntry(bibEntry);
                }
            } catch (OfflineLockException exception) {
                eventBus.post(new UpdateRefusedEvent(bibDatabaseContext, exception.getLocalBibEntry(), exception.getSharedBibEntry()));
            } catch (SQLException e) {
                LOGGER.error("SQL Error: ", e);
            }
        }
    }

    /**
     * Synchronizes the local BibEntries and applies the fetched MetaData on them.
     */
    @Override
    public void pullChanges() {
        if (!checkCurrentConnection()) {
            return;
        }
        // First synchronize entry, then synchronize database
        pullWithLastEntry();
        synchronizeLocalDatabase();
        synchronizeLocalMetaData();
    }

    // Synchronizes local BibEntries only if last entry changes still remain
    public void pullLastEntryChanges() {
        if (!lastEntryChanged.isEmpty()) {
            if (!checkCurrentConnection()) {
                return;
            }
            synchronizeLocalMetaData();
            pullWithLastEntry();
            synchronizeLocalDatabase(); // Pull changes for the case that there were some
        }
    }

    // Synchronizes local BibEntries and pulls remaining last entry changes
    private void pullWithLastEntry() {
        pullWithLastEntry(null);
    }

    // Synchronizes local BibEntries and pulls remaining last entry changes
    private void pullWithLastEntry(BibEntry changedBibEntry) {
        // check if last bib entry is the bib entry that is currently modified: If true, the entry will be synchronized in the listener
        if (!lastEntryChanged.isEmpty() && isPresentLocalBibEntry(lastEntryChanged.get()) && (lastEntryChanged.get() != changedBibEntry)) {
            synchronizeSharedEntry(lastEntryChanged.get());
        }
        lastEntryChanged = Optional.empty();
    }

    /**
     * Checks whether the current SQL connection is valid. In case that the connection is not valid a new {@link
     * ConnectionLostEvent} is going to be sent.
     *
     * @return <code>true</code> if the connection is valid, else <code>false</code>.
     */
    public boolean checkCurrentConnection() {
        try {
            boolean isValid = currentConnection.isValid(0);
            if (!isValid) {
                LOGGER.warn("Lost SQL connection.");
                eventBus.post(new ConnectionLostEvent(bibDatabaseContext));
            }
            return isValid;
        } catch (SQLException e) {
            LOGGER.error("SQL Error:", e);
            return false;
        }
    }

    /**
     * Checks whether the {@link EntriesEventSource} of an {@link EntriesEvent} is crucial for this class.
     *
     * @param event An {@link EntriesEvent}
     * @return <code>true</code> if the event is able to trigger operations in {@link DBMSSynchronizer}, else
     * <code>false</code>
     */
    public boolean isEventSourceAccepted(EntriesEvent event) {
        EntriesEventSource eventSource = event.getEntriesEventSource();
        return ((eventSource == EntriesEventSource.LOCAL) || (eventSource == EntriesEventSource.UNDO));
    }

    @Override
    public void openSharedDatabase(DatabaseConnection connection) throws DatabaseNotSupportedException {
        this.dbName = connection.getProperties().getDatabase();
        this.currentConnection = connection.getConnection();
        this.dbmsProcessor = DBMSProcessor.getProcessorInstance(connection);
        initializeDatabases();
    }

    @Override
    public void closeSharedDatabase() {
        // Submit remaining entry changes
        pullLastEntryChanges();
        try {
            dbmsProcessor.stopNotificationListener();
            currentConnection.close();
        } catch (SQLException e) {
            LOGGER.error("SQL Error:", e);
        }
    }

    private boolean isPresentLocalBibEntry(BibEntry bibEntry) {
        return bibDatabase.getEntries().contains(bibEntry);
    }

    @Override
    public String getDBName() {
        return dbName;
    }

    public DBMSProcessor getDBProcessor() {
        return dbmsProcessor;
    }

    @Override
    public DatabaseConnectionProperties getConnectionProperties() {
        return dbmsProcessor.getDBMSConnectionProperties();
    }

    public void setMetaData(MetaData metaData) {
        this.metaData = metaData;
    }

    @Override
    public void registerListener(Object listener) {
        eventBus.register(listener);
    }
}
