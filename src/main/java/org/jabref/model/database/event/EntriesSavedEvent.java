package org.jabref.model.database.event;

import java.util.List;

import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.event.EntriesEvent;
import org.jabref.model.entry.event.EntriesEventSource;

/**
 * This Event is fired from {@link org.jabref.logic.autosaveandbackup.AutosaveManager} in case that a save task is pending.
 */
public class EntriesSavedEvent extends EntriesEvent {

    /**
     * @param bibEntries <code>List</code> of <code>BibEntry</code> objects which are being saved.
     */
    public EntriesSavedEvent(List<BibEntry> bibEntries) {
        super(bibEntries);
    }

    /**
     * @param bibEntries <code>List</code> of <code>BibEntry</code> objects which are being saved.
     * @param location   Location affected by this event
     */
    public EntriesSavedEvent(List<BibEntry> bibEntries, EntriesEventSource location) {
        super(bibEntries, location);
    }
}
