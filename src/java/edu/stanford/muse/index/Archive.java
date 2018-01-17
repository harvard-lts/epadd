/*
 * Copyright (C) 2012 The Stanford MobiSocial Laboratory
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.stanford.muse.index;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import edu.stanford.muse.Config;
import edu.stanford.muse.datacache.Blob;
import edu.stanford.muse.datacache.BlobStore;
import edu.stanford.muse.email.*;
import edu.stanford.muse.AddressBookManager.AddressBook;
import edu.stanford.muse.AddressBookManager.Contact;
import edu.stanford.muse.AddressBookManager.CorrespondentAuthorityMapper;
import edu.stanford.muse.LabelManager.Label;
import edu.stanford.muse.LabelManager.LabelManager;
import edu.stanford.muse.ie.NameInfo;
import edu.stanford.muse.ie.variants.EntityBook;
import edu.stanford.muse.ner.NER;
import edu.stanford.muse.ner.model.NEType;
import edu.stanford.muse.util.*;
import edu.stanford.muse.webapp.EmailRenderer;
import edu.stanford.muse.webapp.ModeConfig;
import edu.stanford.muse.webapp.SimpleSessions;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.document.Field;
import org.apache.lucene.queryparser.classic.ParseException;
import org.joda.time.DateTime;
import org.json.JSONArray;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Core data structure that represents an archive. Conceptually, an archive is a
 * collection of indexed messages (which can be incrementally updated), along
 * with a blob store. It also has addressbooks, group assigner etc, which are
 * second order properties -- they may be updated independently of the docs (in
 * the future). allDocs is the indexed docs, NOT the ones in the current
 * filter... need to work this out. An archive should be capable of being loaded
 * up in multiple sessions simultaneously. one problem currently is that
 * summarizer is stored in indexer -- however, we should pull it out into
 * per-session state.
 *
 */
public class Archive implements Serializable {
    private static final Log log = LogFactory.getLog(Archive.class);
    private static final long serialVersionUID = 1L;

    // the archive structure: the archive's top level dir has these subdirs
    public static final String BLOBS_SUBDIR = "blobs";
    public static final String TEMP_SUBDIR = "tmp";
    public static final String INDEXES_SUBDIR = "indexes";
    public static final String SESSIONS_SUBDIR = "sessions"; // original idea was that there would be different sessions on the same archive (index). but in practice we only have one session
    private static final String LEXICONS_SUBDIR = "lexicons";
    private static final String FEATURES_SUBDIR = "mixtures";
    public static final String IMAGES_SUBDIR = "images";
    public static final String ADDRESSBOOK_SUFFIX = "AddressBook";
    public static final String ENTITYBOOK_SUFFIX = "EntityBook";
    public static final String CAUTHORITYMAPPER_SUFFIX= "CorrespondentAuthorities";
    public static final String LABELMAPDIR= "LabelMapper";

    public enum Export_Mode {EXPORT_APPRAISAL_TO_PROCESSING,EXPORT_PROCESSING_TO_DELIVERY,EXPORT_PROCESSING_TO_DISCOVERY}
    public static String[] LEXICONS =  new String[]{"default.english.lex.txt"}; // this is the default, for Muse. EpaddIntializer will set it differently. don't make it final

    ////////////  CACHE variables ///////////////
    // these 5 variables cache the list of all entities/blob names/annotations/folders/ email sources in the archive
    // of these folders, email source and blob names generally don't change
    // however, entities and annotations must be recomputed any time there is a change
    // for good measure, we invalidate all of them when close() is called on the archive
    private transient Set<String> allEntities, allBlobNames, allAnnotations, allFolders, allEmailSources;
    ////////////  END CACHE variables ///////////////

    /* all of the following don't change based on the current filter */
    public Indexer indexer;
    private IndexOptions indexOptions;
    public BlobStore blobStore;
    public transient AddressBook addressBook; //transient because it is saved explicitly
    transient private Map<String, Lexicon> lexiconMap = null;
    private List<Document> allDocs;                                                    // this is the equivalent of fullEmailDocs earlier
    transient private Set<Document> allDocsAsSet = null;
    private Set<FolderInfo> fetchedFolderInfos = new LinkedHashSet<>();    // keep this private since its updated in a controlled way
    transient private LinkedHashMap<String, FolderInfo> fetchedFolderInfosMap = null;
    public Set<String> ownerNames = new LinkedHashSet<>(), ownerEmailAddrs = new LinkedHashSet<>();
    private transient EntityBook entityBook;//transient because it is saved explicitly
    public transient CorrespondentAuthorityMapper correspondentAuthorityMapper; /* transient because this is saved and loaded separately */
    private Map<String, NameInfo> nameMap;

    private transient LabelManager labelManager; //transient because it will be saved and loaded separately

    public transient ProcessingMetadata processingMetadata = new ProcessingMetadata();//setting it as transient since v5 as it will be stored/read separately
    public List<FetchStats> allStats = new ArrayList<FetchStats>(); // multiple stats because usually there is 1 per import

    public String archiveTitle; // this is the name of this archive

    public synchronized CorrespondentAuthorityMapper getCorrespondentAuthorityMapper() throws IOException, ParseException, ClassNotFoundException {
        // auth mapper is transient, so may have to be created each time. but it will be loaded from a file if it already exists
        if (correspondentAuthorityMapper == null)
            correspondentAuthorityMapper = CorrespondentAuthorityMapper.createCorrespondentAuthorityMapper(this);
        return correspondentAuthorityMapper;
    }
    /** recreates the authority mapper, call this, e.g. if the address book changes. */
    public synchronized void recreateCorrespondentAuthorityMapper() throws IOException, ParseException, ClassNotFoundException {
        correspondentAuthorityMapper= CorrespondentAuthorityMapper.createCorrespondentAuthorityMapper(this);
    }

    public synchronized  LabelManager getLabelManager() {
        if(labelManager == null)
            labelManager = new LabelManager();
        return labelManager;
    }

    public void setLabelManager(LabelManager labelManager) {
        this.labelManager = labelManager;
    }


    public void setLabels(Collection<Document> docs, Set<String> labelIDs){
        docs.forEach(doc-> labelManager.setLabels(doc.getUniqueId(),labelIDs));
    }

    public void unsetLabels(Collection<Document> docs, Set<String> labelIDs){
        docs.forEach(doc-> labelManager.unsetLabels(doc.getUniqueId(),labelIDs));
    }

    public void putOnlyTheseLabels(Collection<Document> docs, Set<String> labelIDs){
        docs.forEach(doc-> labelManager.putOnlyTheseLabels(doc.getUniqueId(),labelIDs));
    }
    //get all labels for an email document and a given type
    public Set<String> getLabelIDs(EmailDocument edoc){
        return labelManager.getLabelIDs(edoc.getUniqueId());
    }

    public synchronized EntityBook getEntityBook() {
        if (entityBook == null)
            entityBook = new EntityBook();
        return entityBook;
    }
    public synchronized  void setEntityBook(EntityBook eb){
        entityBook = eb;
    }

    /*
     * baseDir is used loosely... it may not be fully reliable, e.g. when the
     * archive moves.
     */
    public String baseDir;


    public SentimentStats stats = new SentimentStats();

    // clusters are somewhat ephemeral and not necessarily a core part of the
    // Archive struct. consider moving it elsewhere.
    private List<MultiDoc> docClusters;

    /**
     * @return all the links extracted from the archive content*/
    //This is a better location for this than Indexer, I (@vihari) think
    private List<LinkInfo> getLinks() {
        return indexer.links;
    }

    public Set<Blob> blobsForQuery(String term){return indexer.blobsForQuery(term);}

    public Collection<edu.stanford.muse.index.Document> docsForQuery(String term, int cluster, int threshold, Indexer.QueryType qt){
        Indexer.QueryOptions options = new Indexer.QueryOptions();
        options.setQueryType(qt);
        options.setCluster(cluster);
        options.setThreshold(threshold);
        return indexer.docsForQuery(term, options);
    }

    public Collection<edu.stanford.muse.index.Document> docsForQuery(String term, int cluster, int threshold) {
        Indexer.QueryOptions options = new Indexer.QueryOptions();
        options.setCluster(cluster);
        options.setThreshold(threshold);
        return indexer.docsForQuery(term, options);
    }

    public Collection<edu.stanford.muse.index.Document> docsForQuery(String term, int cluster, Indexer.QueryType qt) {
        Indexer.QueryOptions options = new Indexer.QueryOptions();
        options.setCluster(cluster);
        return indexer.docsForQuery(term, options);
    }

    Collection<Document> docsForQuery(int cluster, Indexer.QueryType qt) {
        Indexer.QueryOptions options = new Indexer.QueryOptions();
        options.setCluster(cluster);
        options.setQueryType(qt);
        return indexer.docsForQuery(null, options);
    }

    public Collection<edu.stanford.muse.index.Document> docsForQuery(String term, Indexer.QueryType qt) {
        Indexer.QueryOptions options = new Indexer.QueryOptions();
        options.setQueryType(qt);
        return indexer.docsForQuery(term, options);
    }

    /** VIP method: main way to search for documents with the term (embedded in options) in the archive*/
    public Collection<Document> docsForQuery(String term, Indexer.QueryOptions options){
        return indexer.docsForQuery(term, options);
    }


    /**
     * @param q - query
     * @param qt - query type
     * @return number of hits for the query*/
    public int countHitsForQuery(String q, Indexer.QueryType qt) {
        return indexer.countHitsForQuery(q, qt);
    }

    public int countHitsForQuery(String q) {
        return indexer.countHitsForQuery(q, Indexer.QueryType.FULL);
    }

    public Pair<String,String> getContentsOfAttachment(String fileName){
        return indexer.getContentsOfAttachment(fileName);
    }

    public EmailDocument docForId(String id){ return indexer.docForId(id);}


    public String getTitle(org.apache.lucene.document.Document doc){
        return indexer.getTitle(doc);
    }

    public Indexer.IndexStats getIndexStats(){
        return indexer.stats;
    }

    static public class AccessionMetadata implements java.io.Serializable {
        private final static long serialVersionUID = 1L; // compatibility
        public String id, title, date, scope, rights, notes;

    }

    // these fields are used in the library setting
    static public class ProcessingMetadata implements java.io.Serializable {
        private final static long serialVersionUID = 6304656466358754945L; // compatibility
        public String institution, repository, collectionTitle, collectionID, findingAidLink, catalogRecordLink, contactEmail, rights, notes;
        public long timestamp;
        public String tz;
        public int nDocs, nIncomingMessages, nOutgoingMessages, nHackyDates; // note a message can be both incoming and outgoing.
        public int nBlobs, nUniqueBlobs, nImageBlobs, nDocBlobs, nOtherBlobs; // this is just a cache so we don't have to read the archive
        public String ownerName, about;
        //will be set by method that computes epadd-ner
        public Map<Short, Integer> entityCounts;
        public int numPotentiallySensitiveMessages = -1;
        public Date firstDate, lastDate;
        public List<AccessionMetadata> accessionMetadatas;

        public String toJSON() {
            return new Gson().toJson(this);
        }

        private static String mergeField(String a, String b) {
            if (a == null)
                return b;
            if (b == null)
                return a;
            if (a.equals(b))
                return a;
            else
                return a + "+" + b;
        }

        public void merge(ProcessingMetadata other) {
            mergeField(this.institution, other.institution);
            mergeField(this.repository, other.repository);
            mergeField(this.collectionTitle, other.collectionTitle);
            mergeField(this.collectionID, other.collectionID);
            mergeField(this.findingAidLink, other.findingAidLink);
            mergeField(this.catalogRecordLink, other.catalogRecordLink);
            mergeField(this.contactEmail, other.contactEmail);
            mergeField(this.rights, other.rights);
            mergeField(this.notes, other.notes);
            // mergeField(this.tz, other.tz);
        }
    }


    /**
     * set the base dir of the archive, this is the place where all the archive cache is dumped
     * */
    public void setBaseDir(String dir) {
        baseDir = dir;
        blobStore.setDir(dir + File.separator + BLOBS_SUBDIR);
    }

    /**
     * Internal, please do not use!
     * */
    //is being used in types.jsp -> Can we get rid of types.jsp or this call?
    public void setNameMap(Map<String, NameInfo> nameMap) {
        this.nameMap = nameMap;
    }

    public class SentimentStats implements Serializable { // this is a placeholder
        // right now.. its
        // essentially storing
        // archive cluer's stats
        private final static long serialVersionUID = 1L;
        public Map<String, Integer> sentimentCounts;
    }


    private void setBlobStore(BlobStore blobStore) {
        this.blobStore = blobStore;
    }

    //TODO: this should not be public, being used in doSimpleFlow. At least put some some documentation
    //TODO: this should not be public, being used in doSimpleFlow.
    public void setAddressBook(AddressBook ab) {
        addressBook = ab;
    }

    public BlobStore getBlobStore() {
        return blobStore;
    }

    public AddressBook getAddressBook() {
        return addressBook;
    }

    /** private constructor -- always use createArchive() instead */
    private Archive() { }

    public static Archive createArchive() { return createArchive (""); }

    private static Archive createArchive(String title) {
        Archive archive = new Archive();
        archive.archiveTitle = title;
        return archive;
    }

    public synchronized void openForRead() {
        log.info("Opening archive read only");
        indexer.setupForRead();
    }

    public synchronized void openForWrite() throws IOException {
        log.info("Opening archive for write");

        indexer.setupForWrite();
        if (allDocs != null) {
            // we already have some docs in the index, verify it to make
            // sure the archive's idea of #docs is the same as the index's.
            int docsInIndex = indexer.nDocsInIndex();
            log.info(docsInIndex + " doc(s) in index, " + allDocs.size() + " doc(s) in archive");
            Util.warnIf(indexer.nDocsInIndex() != allDocs.size(),
                    "Warning: archive nDocsInIndex is not the same as Archive alldocs (possible if docs have been deleted?)", log);
        }
    }

    public synchronized void close() {
        log.info("Closing archive");
        if (indexer != null)
            indexer.close();
        try {
            if (blobStore != null)
                blobStore.pack(); // ideally, do this only if its dirty
        } catch (Exception e) {
            Util.print_exception(e, log);
        }

        // clear all the caches, so they will be recomputed at next use
        allEntities = allBlobNames = allFolders = allEmailSources = allAnnotations = null;
    }

    // create a new/empty archive.
    // baseDir is for specifying base location of Indexer's file-based
    // directories
    /**
     * Setup an archive
     * @param baseDir - base dir of the archive
     * @param blobStore - attchmane blob store
     * @param args - options for loading @see{edu.stanford.muse.webapp.JSPHelper.preparedArchive}, set to nul to empty array for defaults
     * */
    public void setup(String baseDir, BlobStore blobStore, String args[]) throws IOException {
        prepareBaseDir(baseDir);
        lexiconMap = createLexiconMap(baseDir);
        indexOptions = new IndexOptions();
        indexOptions.parseArgs(args);
        log.info("Index options are: " + indexOptions);
        indexer = new Indexer(baseDir, indexOptions);
        if(blobStore!=null)
            setBlobStore(blobStore);
    }

    /**
     * clear all fields, use when indexer needs to be completely cleared
     */
    public void clear() {
        if (indexer != null)
            indexer.clear();
        if (allDocs != null)
            allDocs.clear();
        if (allDocsAsSet != null)
            allDocsAsSet.clear();
        ownerEmailAddrs.clear();
        ownerNames.clear();
        addressBook = null;
    }

    /*
         * should happen rarely, only while exporting session. fragile operation,
         * make sure blobStore etc are updated consistently
         */
    public void setAllDocs(List<Document> docs) {
        log.info("Updating archive's alldocs to new list of " + docs.size() + " docs");
        allDocs = docs;
        allDocsAsSet = null;

        // reset all these fields, they will be computed afresh
        allEntities = allBlobNames = allAnnotations = allFolders = allEmailSources = null;
    }

    public NameInfo nameLookup(String name) {
        String ctitle = name.toLowerCase().replaceAll(" ", "_");
        if (nameMap != null)
            return nameMap.get(ctitle);
        else
            return null;
    }

    private void addOwnerName(String name) {
        ownerNames.add(name);
        processingMetadata.ownerName = name;
    }

    private void addOwnerEmailAddrs(Collection<String> emailAddrs) {
        ownerEmailAddrs.addAll(emailAddrs);
    }

    public void addOwnerEmailAddr(String emailAddr) {
        ownerEmailAddrs.add(emailAddr);
    }

    /**
     * This should be the only place that creates the cache dir.
     */
    public static void prepareBaseDir(String dir) {
        dir = dir + File.separatorChar + LEXICONS_SUBDIR;
        File f_dir = new File(dir);

        f_dir.mkdirs();

        // copy lexicons over to the muse dir
        // unfortunately, hard-coded because we are loading as a ClassLoader resource and not as a file, so we can't use Util.filesWithSuffix()
        // we have a different set of lexicons for epadd and muse which will be set up in LEXICONS by the time we reach here
        log.info("copying " + LEXICONS.length + " lexicons to " + dir);
        for (String l : LEXICONS) {
            try {

                if (new File(dir+File.separator + l).exists()) {
                    log.info ("Skipping lexicon " + l + " because it already exists");
                    continue;
                }

                InputStream is = EmailUtils.class.getClassLoader().getResourceAsStream("lexicon/" + l);
                if (is == null) {
                    log.warn("lexicon lexicon/" + l + " not found");
                    continue;
                }

                log.info("copying " + l + " to " + dir);
                Util.copy_stream_to_file(is, dir + File.separator + l);
            } catch (Exception e) {
                Util.print_exception(e, log);
            }
        }
    }

    /** adds alternateEmailAddrs if specified in the request to the session. alternateEmailAddrs are simply appended to. */
    public void updateUserInfo(String name, String archiveTitle, String alteranteEmailAddrs)
    {
        // set up the owner email addrs from the email addrs saved in the fetcher's stores
        if (!Util.nullOrEmpty(name))
            this.addOwnerName(name);
        if (!Util.nullOrEmpty(archiveTitle))
            this.archiveTitle = archiveTitle;

        if (!Util.nullOrEmpty(alteranteEmailAddrs))
            this.addOwnerEmailAddrs(EmailUtils.parseAlternateEmailAddrs(alteranteEmailAddrs));
    }
    /**
     * returns the final, sorted, deduped version of allDocs that this driver
     * worked on in its last run
     */
    public List<Document> getAllDocs() {
        if (allDocs == null) {
            synchronized (this) {
                if (allDocs == null) {
                    allDocs = new ArrayList<>();
                    allDocsAsSet = new LinkedHashSet<>();
                }
            }
        }
        return allDocs;
    }

    public Set<Document> getAllDocsAsSet() {
        // allDocsAsSet is lazily computed
        if (allDocsAsSet == null) {
            synchronized (this) {
                if (allDocsAsSet == null) {
                    allDocsAsSet = new LinkedHashSet<>(getAllDocs());
                    Util.softAssert(allDocs.size() == allDocsAsSet.size(),log);
                }
            }
        }
        return allDocsAsSet;
    }

    public void Verify() {
        List<Document> docs = getAllDocs();
        for (Document doc : docs) {
            try {
                org.apache.lucene.document.Document ldoc = this.getLuceneDoc(doc.getUniqueId());
                if (ldoc == null) {
                    log.warn("Some serious error: For document " + doc + " no lucene doc found");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    // work in progress - status provider
    public StatusProvider getStatusProvider() {
        return indexer;
    }

    public Map<String, Collection<Document>> getSentimentMap(Lexicon lex, boolean originalContentOnly, String... captions) {
        if (lex == null) {
            log.warn ("Warning: lexicon is null!");
            return new LinkedHashMap<>();
        }
        return lex.getEmotions(indexer, getAllDocsAsSet(), false /* doNota */, originalContentOnly, captions);
    }

    /**
     * gets original content only!
     */
    public String getContents(Document d, boolean originalContentOnly) {
        return indexer.getContents(d, originalContentOnly);
    }

    public String getContents(org.apache.lucene.document.Document ldoc, boolean originalContentOnly){
        return indexer.getContents(ldoc, originalContentOnly);
    }

    private void setupAddressBook(List<Document> docs) {
        // in this case, we don't care whether email addrs are incoming or
        // outgoing,
        // so the ownAddrs can be just a null string
        if (addressBook == null)
            addressBook = new AddressBook(new String[0], new String[0]);
        log.info("Setting up address book for " + docs.size() + " messages (indexing driver)");
        for (Document d : docs)
            if (d instanceof EmailDocument)
                addressBook.processContactsFromMessage((EmailDocument) d);

        addressBook.organizeContacts();
    }

    public List<LinkInfo> extractLinks(Collection<Document> docs) throws Exception {
        prepareAllDocs(docs, indexOptions);
        indexer.clear();
        indexer.extractLinks(docs);
        return EmailUtils.getLinksForDocs(docs);
    }

    public Collection<DatedDocument> docsInDateRange(Date start, Date end) {
        List<DatedDocument> result = new ArrayList<>();
        if (Util.nullOrEmpty(allDocs))
            return result;

        for (Document d : allDocs) {
            try {
                DatedDocument dd = (DatedDocument) d;
                if ((dd.date.after(start) && dd.date.before(end)) || dd.date.equals(start) || dd.date.equals(end))
                    result.add(dd);
            } catch (Exception e) {
                Util.print_exception(e, log);
            }
        }
        return result;
    }

    public boolean containsDoc(Document doc) {
        return getAllDocsAsSet().contains(doc);
    }

    /**
     * use with caution. pseudo-adds a doc to the archive, but without any
     * subject and without any contents. useful only when doing quick screening
     * to check of emails for memory tests, etc.
     */
    public synchronized boolean addDocWithoutContents(Document doc) {
        if (containsDoc(doc))
            return false;

        getAllDocsAsSet().add(doc);
        getAllDocs().add(doc);

        String subject = "", contents = "";

        indexer.indexSubdoc(subject, contents, doc, blobStore);

        if (getAllDocs().size() % 100 == 0)
            log.info("Memory status after " + getAllDocs().size() + " emails: " + Util.getMemoryStats());

        return true;
    }

    private synchronized List<LinkInfo> postProcess(Collection<Document> docs) {
        // should we sort the messages by time here?

        log.info(indexer.computeStats());
        log.info(getLinks().size() + " links");
        // prepareAllDocs(docs, io);
        // TODO: should we recomputeCards? call nukeCards for now to invalidate
        // cards since archive may have been modified.

        List<LinkInfo> links = getLinks();
        return links;
    }

    /**
     * core method, adds a single doc to the archive. remember to call
     * postProcess at the end of any series of calls to add docs
     */
    public synchronized boolean addDoc(Document doc, String contents) {
        if (containsDoc(doc))
            return false;

        getAllDocsAsSet().add(doc);
        getAllDocs().add(doc);

        String subject = doc.getSubjectWithoutTitle();
        subject = EmailUtils.cleanupSubjectLine(subject);

        indexer.indexSubdoc(subject, contents, doc, blobStore);

        if (getAllDocs().size() % 100 == 0)
            log.info("Memory status after " + getAllDocs().size() + " emails: " + Util.getMemoryStats());

        return true;
    }

    /**
     * prepares all docs for indexing, incl. applying filters, removing dups and
     * sorting
     *
     * @throws Exception
     */
    private void prepareAllDocs(Collection<Document> docs, IndexOptions io) throws Exception {
        allDocs = new ArrayList<>();
        allDocs.addAll(docs);
        allDocs = EmailUtils.removeDupsAndSort(allDocs);
        log.info(allDocs.size() + " documents after removing duplicates");

        if (addressBook == null && !io.noRecipients) {
            log.warn("no address book previously set up!");
            setupAddressBook(allDocs); // set up without the benefit of ownaddrs
        }

        if (io.filter != null && addressBook != null) {
            Contact ownCI = addressBook.getContactForSelf(); // may return null
            // if we don't
            // have own info
            io.filter.setOwnContactInfo(ownCI);
        }

        // if no filter, accept doc (default)
        List<Document> newAllDocs = new ArrayList<>();
        for (Document d : allDocs)
            if (io.filter == null || (io.filter != null && io.filter.matches(d)))
                newAllDocs.add(d);

        EmailUtils.cleanDates(newAllDocs);

        log.info(newAllDocs.size() + " documents after filtering");

        allDocs = newAllDocs;
        Collections.sort(allDocs); // may not be essential
        allDocsAsSet = null;
    }

    private String getFolderInfosMapKey(String accountKey, String longName) {
        return accountKey + "..." + longName;
    }

    private void setupFolderInfosMap() {
        if (fetchedFolderInfosMap == null)
            fetchedFolderInfosMap = new LinkedHashMap<>();
        for (FolderInfo fi : fetchedFolderInfos) {
            fetchedFolderInfosMap.put(getFolderInfosMapKey(fi.accountKey, fi.longName), fi);
        }
    }

    /**
     * adds a collection of folderinfo's to the archive, updating existing ones
     * as needed
     */
    public void addFetchedFolderInfos(Collection<FolderInfo> fis) {
        // if a folderinfo with the same accountKey and longname already exists,
        // its lastSeenUID may need to be updated.

        // first organize a key -> folder info map in case we have a large # of
        // folders
        setupFolderInfosMap();

        for (FolderInfo fi : fis) {
            String key = getFolderInfosMapKey(fi.accountKey, fi.longName);
            FolderInfo existing_fi = fetchedFolderInfosMap.get(key);
            if (existing_fi != null) {
                if (existing_fi.lastSeenUID < fi.lastSeenUID)
                    existing_fi.lastSeenUID = fi.lastSeenUID;
            } else {
                fetchedFolderInfos.add(fi);
                fetchedFolderInfosMap.put(key, fi);
            }
        }
    }

    private FolderInfo getFetchedFolderInfo(String accountID, String fullFolderName) {
        setupFolderInfosMap();
        return fetchedFolderInfosMap.get(getFolderInfosMapKey(accountID, fullFolderName));
    }

    /**
     * returns last seen UID for the specified folder, -1 if its not been seen
     * before
     */
    public long getLastUIDForFolder(String accountID, String fullFolderName) {
        FolderInfo existing_fi = getFetchedFolderInfo(accountID, fullFolderName);
        if (existing_fi != null)
            return existing_fi.lastSeenUID;
        else {
            return -1L;
        }
    }

    /***/
    public List<LinkInfo> postProcess() {
        return postProcess(allDocs);
    }

    // replace subject with extracted names
    private static void replaceDescriptionWithNames(Collection<? extends Document> allDocs, Archive archive) throws Exception {
        for (Document d : allDocs) {
            if (!Util.nullOrEmpty(d.description)) {
                //log.info("Replacing description for docId = " + d.getUniqueId());
                // List<String> names =
                // Indexer.extractNames(d.description);
                // Collections.sort(names);
                // d.description = Util.join(names,
                // Indexer.NAMES_FIELD_DELIMITER);
                d.description = IndexUtils.retainOnlyNames(d.description, archive.getLuceneDoc(d.getUniqueId()));
            }
        }
    }

    /**
     * export archive with just the given docs to prepare for public mode.
     * docsToExport should be a subset of what's already in the archive. returns
     * true if successful.
     */
	/*
	 * public boolean trimArchive(Collection<EmailDocument> docsToRetain) throws
	 * Exception { if (docsToRetain == null) return true; // return without
	 * doing anything
	 * 
	 * // exports messages in current filter (allEmailDocs) //HttpSession
	 * session = request.getSession(); Collection<Document> fullEmailDocs =
	 * this.getAllDocs(); Indexer indexer = sthis.indexer;
	 * 
	 * // compute which docs to remove vs. keep Set<Document> docsToKeep = new
	 * LinkedHashSet<Document>(docsToRetain); Set<Document> docsToRemove = new
	 * LinkedHashSet<Document>(); for (Document d: fullEmailDocs) if
	 * (!docsToKeep.contains(d)) docsToRemove.add(d);
	 * 
	 * // remove unneeded docs from the index
	 * indexer.removeEmailDocs(docsToRemove); // CAUTION: permanently change the
	 * index! this.setAllDocs(new ArrayList<Document>(docsToRetain)); return
	 * true; }
	 */


    /** Get docs for export based on the module for which exporting is taking place*/
    public List<Document> getDocsForExport(Export_Mode mode) {
        List<Document> docsToExport = new LinkedList<>();
        if (mode == Export_Mode.EXPORT_APPRAISAL_TO_PROCESSING) {
            //any message with DNT label will not be transferred
            for (Document d : getAllDocs()) {
                EmailDocument ed = (EmailDocument) d;
                //export the doc only if it does not contain DNT label
                if (!getLabelIDs(ed).contains(LabelManager.LABELID_DNT))
                    docsToExport.add(d);
            }
        } else if (mode == Export_Mode.EXPORT_PROCESSING_TO_DELIVERY || mode == Export_Mode.EXPORT_PROCESSING_TO_DISCOVERY) {
            //get a set of general restriction ids from labelManager
            //get a set of timed-restriction ids from LabelManager
            Set<Label> allRestrictions = getLabelManager().getAllLabels(LabelManager.LabType.RESTR_LAB);
            Set<String> genRestriction = new HashSet<>();
            Set<String> timedRestriction = new HashSet<>();
            allRestrictions.forEach(label -> {
                if (label.getRestrictionType() == LabelManager.RestrictionType.OTHER)
                    genRestriction.add(label.getLabelID());
                else
                    timedRestriction.add(label.getLabelID());
            });
            //any message with any general restriction label (including DNT) will not be transferred
            //any message with timed-restriction label whose date is not over, will not be transferred
            //everything else will be transferred.
            //@TODO- Not yet implemented the labelAppliesToText part of label.
            for (Document d : getAllDocs()) {
                EmailDocument ed = (EmailDocument) d;
                //Do not export if it contains any general restriction label
                if (Util.setIntersection(getLabelIDs(ed), genRestriction).size() != 0) {
                    //means at least one general restriction label on this doc, do not export it.
                    //nothing to be done
                } else if (Util.setIntersection(getLabelIDs(ed), timedRestriction).size() != 0) {
                    //0.If we can not find the correct time of this document then be conservative and
                    //dont' export it.
                    if(EmailFetcherThread.INVALID_DATE.equals(ed.getDate()))
                        continue;
                    Date dt = ed.getDate();
                    //1.means at least one timed restriction label on this doc. Check for the timed data
                    //if it is past current date/time then export else dont'
                    //2.get those timed restrictions
                    Set<String> timedrestrictions = Util.setIntersection(getLabelIDs(ed),timedRestriction);
                    //if any of the timedrestriction is not satisfied then don't export it.
                    for(String labid: timedRestriction){
                        Label l = getLabelManager().getLabel(labid);
                        if(l.getRestrictionType()== LabelManager.RestrictionType.RESTRICTED_FOR_YEARS){
                            int year = l.getRestrictedForYears();
                            Date future = new DateTime(dt).plusYears(year).toDate();
                            Date today = new DateTime().toDate();
                            if(future.before(today))//means the time is over
                                docsToExport.add(d);//add doc
                        }else if(l.getRestrictionType() == LabelManager.RestrictionType.RESTRICTED_UNTIL){
                            Date today = new DateTime().toDate();
                            if(l.getRestrictedUntilTime()<today.getTime())//means the restriction time is over
                                docsToExport.add(d);//add doc
                        }
                    }
                } else {
                    //else export it
                    docsToExport.add(d);
                }
            }

        }
    return docsToExport;
    }

    /**
     * a fresh archive is created under out_dir. name is the name of the session
     * under it. blobs are exported into this archive dir. destructive! but
     * should be so only in memory. original files on disk should be unmodified.
     *
     * @param retainedDocs
     * @throws Exception
     */
    public synchronized String export(Collection<? extends Document> retainedDocs, Export_Mode export_mode, String out_dir, String name) throws Exception {
        if (Util.nullOrEmpty(out_dir))
            return null;
        File dir = new File(out_dir);
        if (dir.exists() && dir.isDirectory()) {
            log.warn("Overwriting existing directory '" + out_dir + "' (it may already exist)");
            FileUtils.deleteDirectory(dir);
        } else if (!dir.mkdirs()) {
            log.warn("Unable to create directory: " + out_dir);
            return null;
        }
        boolean exportInPublicMode = export_mode==Export_Mode.EXPORT_PROCESSING_TO_DISCOVERY;
        Archive.prepareBaseDir(out_dir);
        if (!exportInPublicMode && new File(baseDir + File.separator + LEXICONS_SUBDIR).exists())
            FileUtils.copyDirectory(new File(baseDir + File.separator + LEXICONS_SUBDIR), new File(out_dir + File.separator + LEXICONS_SUBDIR));
        if (new File(baseDir + File.separator + IMAGES_SUBDIR).exists())
            FileUtils.copyDirectory(new File(baseDir + File.separator + IMAGES_SUBDIR), new File(out_dir + File.separator + IMAGES_SUBDIR));
        //internal disambiguation cache
        if (new File(baseDir + File.separator + FEATURES_SUBDIR).exists())
            FileUtils.copyDirectory(new File(baseDir + File.separator + FEATURES_SUBDIR), new File(out_dir + File.separator + FEATURES_SUBDIR));
        if (new File(baseDir + File.separator + edu.stanford.muse.Config.AUTHORITY_ASSIGNER_FILENAME).exists())
            FileUtils.copyFile(new File(baseDir + File.separator + edu.stanford.muse.Config.AUTHORITY_ASSIGNER_FILENAME), new File(out_dir + File.separator + edu.stanford.muse.Config.AUTHORITY_ASSIGNER_FILENAME));

        // save the states that may get modified
        List<Document> savedAllDocs = allDocs;
        LabelManager oldLabelManager= getLabelManager();
        /////////////////saving done//////////////////////////////////
        //change state of the current archive -temporarily//////////
        if (exportInPublicMode){
            //because message header gets modified so need to create copy of these documents and then set
            //them as allDocs
            allDocs = retainedDocs.stream().map(doc->{
                EmailDocument ed = (EmailDocument)doc;
                return ed.copyMutableFields();
            }).collect(Collectors.toList());
            //replace description with names;
            replaceDescriptionWithNames(allDocs, this);
        }else{
            allDocs = new ArrayList<>(retainedDocs);
        }
        Set<String> retainedDocIDs = retainedDocs.stream().map(Document::getUniqueId).collect(Collectors.toSet());
        LabelManager newLabelManager = getLabelManager().getLabelManagerForExport(retainedDocIDs,export_mode);
        setLabelManager(newLabelManager);
        // copy index and if for public mode, also redact body and remove title
        // fields
        final boolean redact_body_instead_of_remove = true;
        Set<String> docIdSet = new LinkedHashSet<>();
        for (Document d : allDocs)
            docIdSet.add(d.getUniqueId());
        final Set<String> retainedDocIds = docIdSet;
        Indexer.FilterFunctor emailFilter = doc -> {
            if (!retainedDocIds.contains(doc.get("docId")))
                return false;

            if (exportInPublicMode) {
                String text;
                if (redact_body_instead_of_remove) {
                    text = doc.get("body");
                }
                doc.removeFields("body");
                doc.removeFields("body_original");

                if (text != null) {
                    String redacted_text = IndexUtils.retainOnlyNames(text, doc);
                    doc.add(new Field("body", redacted_text, Indexer.full_ft));
                    //this uses standard analyzer, not stemming because redacted bodys only have names.
                }
                String title = doc.get("title");
                doc.removeFields("title");
                if (title != null) {
                    String redacted_title = IndexUtils.retainOnlyNames(text, doc);
                    doc.add(new Field("title", redacted_title, Indexer.full_ft));
                }
            }
            return true;
        };

/*
Moveing it at the end- after changing the basedir of the archive. Because addressbook is getting saved
after maskEmailDomain.
        if (exportInPublicMode) {
            List<Document> docs = this.getAllDocs();
            List<EmailDocument> eds = new ArrayList<>();
            for (Document doc : docs)
                eds.add((EmailDocument) doc);

            EmailUtils.maskEmailDomain(eds, this.addressBook);
        }
*/


        Indexer.FilterFunctor attachmentFilter = doc -> {
            if(exportInPublicMode){
                return false;
            }
            String docId = doc.get("emailDocId");
            if(docId == null){
                Integer di = Integer.parseInt(doc.get("docId"));
                //don't want to print too many messages
                if(di<10)
                    log.error("Looks like this is an old archive, filtering all the attachments!!\n" +
                            "Consider re-indexing with the latest version for a proper export.");
                return false;
            }
            return retainedDocIds.contains(docId);
        };

        indexer.copyDirectoryWithDocFilter(out_dir, emailFilter, attachmentFilter);
        log.info("Completed exporting indexes");

        // save the blobs in a new blobstore
        if (!exportInPublicMode) {
            log.info("Starting to export blobs, old blob store is: " + blobStore);
            Set<Blob> blobsToKeep = new LinkedHashSet<>();
            for (Document d : allDocs)
                if (d instanceof EmailDocument)
                    if (!Util.nullOrEmpty(((EmailDocument) d).attachments))
                        blobsToKeep.addAll(((EmailDocument) d).attachments);
            String blobsDir = out_dir + File.separatorChar + BLOBS_SUBDIR;
            new File(blobsDir).mkdirs();
            BlobStore newBlobStore = blobStore.createCopy(blobsDir, blobsToKeep);
            log.info("Completed exporting blobs, newBlobStore in dir: " + blobsDir + " is: " + newBlobStore);
            // switch to the new blob store (important -- the urls and indexes in the new blob store are different from the old one! */
            blobStore = newBlobStore;
        }
        String oldBaseDir = baseDir;
        //change base directory
        setBaseDir(out_dir);

        if (exportInPublicMode) {
            List<Document> docs = this.getAllDocs();
            List<EmailDocument> eds = new ArrayList<>();
            for (Document doc : docs)
                eds.add((EmailDocument) doc);

            EmailUtils.maskEmailDomain(eds, this.addressBook);
        }


        // write out the archive file
        SimpleSessions.saveArchive(out_dir, name, this); // save .session file.
        log.info("Completed saving archive object");

        // restore states
        setBaseDir(oldBaseDir);
        allDocs = savedAllDocs;
        setLabelManager(oldLabelManager);

        return out_dir;
    }


    //method to return the count map of entities provided that the score of the entity is greater than
    //thersold.
    public static Map<Short,Integer> getEntitiesCountMapModuloThersold(Archive archive, double thersold){
        Map<Short,Integer> entityTypeToCount = new LinkedHashMap<>();
        EntityBook entityBook = archive.getEntityBook();
        Set<String> seen = new LinkedHashSet<>();

        for(Document doc: archive.getAllDocs()) {
            Span[] es1 = archive.getEntitiesInDoc(doc, true);
            Span[] es2 = archive.getEntitiesInDoc(doc, false);
            Set<Span> ss = Arrays.stream(es1).collect(Collectors.toSet());
            Set<Span> ss1 = Arrays.stream(es2).collect(Collectors.toSet());
            ss.addAll(ss1);
            for (Span span : ss) {
                if (span.typeScore < thersold)
                    continue;
                String name = span.getText();
                String displayName = name;

                //  map the name to its display name. if no mapping, we should get the same name back as its displayName
                if (entityBook != null)
                    displayName = entityBook.getDisplayName(name, span.type);

                displayName = displayName.trim();
                if (seen.contains(displayName.toLowerCase()))
                    continue; // count an entity only once

                seen.add(displayName.toLowerCase());
                if (!entityTypeToCount.containsKey(span.getType())) {
                    entityTypeToCount.put(span.getType(), 0);
                }
                entityTypeToCount.put(span.getType(), entityTypeToCount.get(span.getType()) + 1);
            }
        }

        return entityTypeToCount;
    }
    public List<Document> docsWithThreadId(long threadID) {
        List<Document> result = new ArrayList<>();
        for (Document ed : allDocs) {
            if (((EmailDocument) ed).threadID == threadID)
                result.add(ed);
        }
        return result;
    }

    public String getStats() {
        // note: this is a legacy method that does not use the archivestats
        // object above
        StringBuilder sb = new StringBuilder(allDocs.size() + " original docs with " + ownerEmailAddrs.size() + " email addresses " + ownerNames.size()
                + " names for owner ");
        if (addressBook != null)
            sb.append(addressBook.getStats()).append("\n");
        sb.append(indexer.computeStats()).append("\n").append(getLinks().size()).append(" links");
        return sb.toString();
    }

    /**
     * @return html for the given terms, with terms highlighted by the indexer.
     * if IA_links is set, points links to the Internet archive's version of the page.
     * docId is used to initialize a new view created by clicking on a link within this message,
     * date is used to create the link to the IA
     * @args ldoc - lucene doc corresponding to the content
     * s - content of the doc
     * Date
     * docId - Uniquedocid of the emaildocument
     * highlighttermsUnstemmed - terms to highlight in the content (for ex
     * lexicons)
     * highlighttermsstemmed - entities to highlight, generally are names
     * that one doesn't wish to be stemmed.
     * entitiesWithId - authorisedauthorities, for annotation
     * showDebugInfo - enabler to show debug info
     */
    private String annotate(org.apache.lucene.document.Document ldoc, String s, Date date, String docId, String regexToHighlight, Set<String> highlightTerms,
                            Map<String, EmailRenderer.Entity> entitiesWithId, boolean IA_links, boolean showDebugInfo) {
        getAllDocs();
        try {

            s = Highlighter.getHTMLAnnotatedDocumentContents(s, (IA_links ? date : null), docId, regexToHighlight, highlightTerms, entitiesWithId, null /* summarizer.importantTermsCanonical */, false);

            //indexer
            //	.getHTMLAnnotatedDocumentContents(s, (IA_links ? date : null), docId, searchTerms, isRegexSearch, highlightTermsStemmed, highlightTermsUnstemmed, entitiesWithId);
        } catch (Exception e) {
            e.printStackTrace();
            log.warn("indexer failed to annotate doc contents " + Util.stackTrace(e));
        }

        return s;
    }

    public String annotate(String s, Date date, String docId, String regexToHighlight, Set<String> highlightTerms,
                           Map<String, EmailRenderer.Entity> entitiesWithId, boolean IA_links, boolean showDebugInfo) {
        return annotate(null, s, date, docId, regexToHighlight, highlightTerms,
                entitiesWithId, IA_links, showDebugInfo);
    }

    public Pair<StringBuilder, Boolean> getHTMLForContents(Document d, Date date, String docId, String regexToHighlight, Set<String> highlightTerms,
                                                            Map<String, Map<String, Short>> authorisedEntities, boolean IA_links, boolean inFull, boolean showDebugInfo) throws Exception {
        org.apache.lucene.document.Document ldoc = indexer.getDoc(d);
        Span[] names = getAllNamesInLuceneDoc(ldoc,true);

        String contents = indexer.getContents(d, false);
        Set<String> acrs = Util.getAcronyms(contents);

        if (ldoc == null) {
            System.err.println("Lucene Doc is null for: " + d.getUniqueId() + " but the content is " + (contents == null ? "null" : "not null"));
            return null;
        }

        // Contains all entities and id if it is authorised else null
        Map<String, EmailRenderer.Entity> entitiesWithId = new HashMap<>();
        //we annotate three specially recognized types
        Map<Short,String> recMap = new HashMap<>();
        recMap.put(NEType.Type.PERSON.getCode(),"cp");
        recMap.put(NEType.Type.PLACE.getCode(),"cl");
        recMap.put(NEType.Type.ORGANISATION.getCode(),"co");
        Arrays.stream(names).filter(n -> recMap.keySet().contains(NEType.getCoarseType(n.type).getCode()))
                .forEach(n -> {
                    Set<String> types = new HashSet<>();
                    types.add(recMap.get(NEType.getCoarseType(n.type).getCode()));
                    entitiesWithId.put(n.text, new EmailRenderer.Entity(n.text, authorisedEntities == null ? null : authorisedEntities.get(n), types)); // TOFIX: intellij points out a bug here. authorizedEntities.get(n) will always return false because n is a Span
                });
        acrs.forEach(acr->{
            Set<String> types = new HashSet<>();
            types.add("acr");
            entitiesWithId.put(acr,new EmailRenderer.Entity(acr, authorisedEntities==null?null:authorisedEntities.get(acr),types));
        });

        //don't want "more" button anymore
        String htmlContents;
        if (contents.length() > Config.MAX_TEXT_SIZE_TO_ANNOTATE) // don't try to annotate extraordinarily long messages, probably bad data, as discovered on RF archive
            htmlContents = Util.escapeHTML(contents);
        else
            htmlContents = annotate(ldoc, contents, date, docId, regexToHighlight, highlightTerms, entitiesWithId, IA_links, showDebugInfo);

        if (ModeConfig.isPublicMode())
            htmlContents = Util.maskEmailDomain(htmlContents);

        StringBuilder sb = new StringBuilder();
        sb.append(htmlContents);

        boolean overflow = false;
        return new Pair<>(sb, overflow);
    }

    /* break up docs into clusters, based on existing docClusters
    * Note: Clustering Type MONTHLY and YEARLY not supported*/
    public List<MultiDoc> clustersForDocs(Collection<? extends Document> docs, MultiDoc.ClusteringType ct) {
        //TODO: whats the right thing to do when docClusters is null?
        if (docClusters == null || (ct == MultiDoc.ClusteringType.NONE)) {
            List<MultiDoc> new_mDocs = new ArrayList<>();
            MultiDoc md = new MultiDoc(0,"all");
            docs.forEach(md::add);

            new_mDocs.add(md);
            return new_mDocs;
        }

        Map<Document, Integer> map = new LinkedHashMap<>();
        int i = 0;
        for (MultiDoc mdoc : docClusters) {
            for (Document d : mdoc.docs)
                map.put(d, i);
            i++;
        }

        List<MultiDoc> new_mDocs = new ArrayList<>();
        for (MultiDoc md : docClusters)
            new_mDocs.add(null);

        for (Document d : docs) {
            int x = map.get(d);
            MultiDoc new_mDoc = new_mDocs.get(x);
            if (new_mDoc == null) {
                MultiDoc original = docClusters.get(x);
                new_mDoc = new MultiDoc(original.getUniqueId(), original.description);
                new_mDocs.set(x, new_mDoc);
            }
            new_mDoc.add(d);
        }

        List<MultiDoc> result = new ArrayList<>();
        for (MultiDoc md : new_mDocs)
            if (md != null)
                result.add(md);

        return result;
    }

    public String toString() {
        // be defensive here -- some of the fields may be null
        StringBuilder sb = new StringBuilder();
        if (allDocs != null)
            sb.append("Archive with #docs: ").append(allDocs.size()).append(" address book: ").append(addressBook).append(" ").append(getStats()).append(" ");
        else
            sb.append("Null docs");
        if (indexer != null) {
            if (indexer.stats != null)
                sb.append(Util.fieldsToString(indexer.stats, false));
            else
                sb.append("Null indexer-stats");
        } else
            sb.append("Null indexer");
        return sb.toString();
    }

    //TODO retain only one of the two methods below
    public org.apache.lucene.document.Document getLuceneDoc(String docId) throws IOException {
        return indexer.getLDoc(docId);
    }

    public org.apache.lucene.document.Document getLuceneDoc(String docId, Set<String> fieldsToLoad) throws IOException {
        return indexer.getLDoc(docId, fieldsToLoad);
    }

    private Set<String> getNames(edu.stanford.muse.index.Document d, Indexer.QueryType qt)
    {
        try {
            return new LinkedHashSet<>(getNamesForDocId(d.getUniqueId(), qt));
        } catch (Exception e) {
            Util.print_exception(e, log);
            return new LinkedHashSet<>();
        }
    }

    /* body = true => in message body, false => in subject */
    public Span[] getEntitiesInDoc(Document d, boolean body){
        try {
            return edu.stanford.muse.ner.NER.getNames(d, body, this);
        }catch(Exception e) {
            Util.print_exception(e, log);
            return new Span[]{};
        }
    }

    /** returns all entities in the given doc, both in body and subject */
    public synchronized Set<String> getEntitiesInDoc(Document d) {
        Set<String> entities = new LinkedHashSet<>();
        Stream.of(getEntitiesInDoc (d, false)).map(Span::getText).forEach(entities::add);
        Stream.of(getEntitiesInDoc (d, true)).map(Span::getText).forEach(entities::add);
        return entities;
    }

    public synchronized Set<String> getAllEntities() {

        if (allEntities == null) {
            allEntities = new LinkedHashSet<>();
            for (Document d : getAllDocs()) {
                try {
                    Stream.of(getEntitiesInDoc(d,true)).map(Span::getText).forEach(allEntities::add);
                } catch (Exception e) {
                    Util.print_exception("exception reading fine grained entities", e, log);
                }
            }
        }
        return allEntities;
    }

    transient private Multimap<Short, Document> entityTypeToDocs = LinkedHashMultimap.create(); // entity type code -> docs containing it
    private synchronized void computeEntityTypeToDocMap() {
        if (entityTypeToDocs != null)
            return;
        entityTypeToDocs = LinkedHashMultimap.create();
        for(Document doc: this.getAllDocs()){
            Span[] es = this.getEntitiesInDoc(doc,true);
            Set<Short> seenInThisDoc = new LinkedHashSet<>(); // type -> docs: one value should contain a doc only once

            double theta = 0.001;
            for(Span sp: es) {
                if (sp.typeScore < theta)
                    continue;
                if (seenInThisDoc.contains (sp.type))
                    continue;
                seenInThisDoc.add (sp.type);

                entityTypeToDocs.put (sp.type, doc);
            }
        }
    }

    public synchronized Collection<Document> getDocsWithEntityType(short code) {
        return entityTypeToDocs.get (code);
    }

    public synchronized Set<NEType.Type> getEntityTypes() {
        Set<NEType.Type> result = new LinkedHashSet<>();

        computeEntityTypeToDocMap();
        for (short t: entityTypeToDocs.keys()) {
            result.add (NEType.getTypeForCode(t));
        }
        return result;
    }

    //returns a map of names recognised by NER to frequency
    private Map<String, Integer> countNames() {
        Map<String, Integer> name_count = new LinkedHashMap<>();
        for (Document d : getAllDocs()) {
            Set<String> names = getNames(d, Indexer.QueryType.FULL);
            // log.info("Names = " + Util.joinSort(names, "|"));
            for (String n : names) {
                n = n.trim();
                if (n.length() == 0)
                    continue;
                if (name_count.containsKey(n))
                    name_count.put(n, name_count.get(n) + 1);
                else
                    name_count.put(n, 1);
            }
        }

        // for (Map.Entry<String, Integer> e : entries) {
        // log.info("NameCount:" + e.getKey() + "|" + e.getValue());
        // }
        return name_count;
    }

    public List<String> getNamesForDocId(String id, Indexer.QueryType qt) throws IOException
    {
        return indexer.getNamesForDocId(id, qt);
    }

    public List<List<String>> getAllNames(Collection<String> ids, Indexer.QueryType qt) throws IOException
    {
        List<List<String>> result = new ArrayList<>();
        for (String id : ids)
            result.add(getNamesForDocId(id, qt));
        return result;
    }

    /**
     * Assign Ids to threads, can help in making out if two emails belong to the same thread
     * Subject/Title of the a message can also be used for the same purpose
     * @return the maximum thread id value assignbed to any thread in th arhchive*/
    public int assignThreadIds() {
        Collection<Collection<EmailDocument>> threads = EmailUtils.threadEmails((Collection) allDocs);
        int thrId = 1; // note: valid thread ids must be > 1
        for (Collection<EmailDocument> thread : threads) {
            for (EmailDocument doc : thread)
                doc.threadID = thrId;
            thrId++;
        }
        return thrId;
    }

    public void postDeserialized(String baseDir, boolean readOnly) throws IOException {

        log.info(indexer.computeStats());

        indexer.setBaseDir(baseDir);
        openForRead();

        if (!readOnly)
            indexer.setupForWrite();
        if (addressBook != null) {
            // addressBook.reassignContactIds();
            addressBook.organizeContacts(); // is this idempotent?
        }

        if (lexiconMap == null) {
            lexiconMap = createLexiconMap(baseDir);
        }

        // recompute... sometimes the processing metadata may be stale, because some messages have been redacted at export.
      //  processingMetadata.numPotentiallySensitiveMessages = numMatchesPresetQueries();
    }

    /*
    Class for storing the merge result data
     */
    public class MergeResult{
        public int nMessagesInCollection=0;
        public int nAttachmentsInCollection=0;
        public int nMessagesInAccession=0;
        public int nAttachmentsInAccession=0;
        public int nCommonMessages=0;
        public int nFinalMessages=0;
        public int nFinalAttachments=0;
        public String accessionDir;
        ////For AddressBook Merge report
        public AddressBook.MergeResult addressBookMergeResult;
        ///For LabelManager Merge report
        public LabelManager.MergeResult labManagerMergeResult;
        ///For Lexicon merge report
        public Set<String> clashedLexicons;
        public Set<String> newLexicons;
    }

    //////////////////////////Data fields for accessionmerging///////////////////////////
    private transient MergeResult lastMergeResult;
    private Map<String,String> docIDToAccessionID;
    public String baseAccessionID;//This represents the accession ID when an accession is imported
    //to an empty collection. It is used for ensuring a manageable size of docToAccessionMap for common case
    //of single accession archives. While getting to know about the accessionID of a doc if it is not found
    //in the map docToAccessionMap then the ID is assumed to be baseAccessionID. Note that, this id is set
    //from the accession ID of the first accession imported in an empty collection.

    public MergeResult getLastMergeResult(){
        return lastMergeResult;
    }
    private Map<String,String> getDocIDToAccessionID(){
        if(docIDToAccessionID ==null)
            return new LinkedHashMap<>();
        else
            return docIDToAccessionID;
    }

    //Postcondition: variable MergeResult is set
    public void merge(Archive other, String accessionID){
        MergeResult result = new MergeResult();
        /////////////////////////////INDEX MERGING AND DOCUMENT COPYING//////////////////////////
        //for each mail document in other process it only if it is not present in this archive.
        //if not present then call indexer's method to add doc and associated attachments to this index.
        result.nMessagesInAccession = other.getAllDocs().size();
        result.nAttachmentsInAccession = other.blobStore.uniqueBlobs.size();
        result.nMessagesInCollection = getAllDocs().size();
        result.nAttachmentsInCollection = blobStore.uniqueBlobs.size();
        result.accessionDir = other.baseDir;
        for(Document doc: other.getAllDocs()) {
            if (!getAllDocs().contains(doc)) {
                EmailDocument edoc = (EmailDocument) doc;
                try {
                    getAllDocs().add(doc);
                    getAllDocsAsSet().add(doc);
                    //add a field called accession id to these documents.
                    getDocIDToAccessionID().put(edoc.getUniqueId(),accessionID);
                    indexer.moveDocAndAttachmentsToThisIndex(other.indexer, edoc,other.getBlobStore(),blobStore);
                } catch (IOException e) {
                    log.warn("Unable to copy document with signature" + ((EmailDocument) doc).getSignature() + " from the incoming archive to this archive ");
                    e.printStackTrace();
                }
            }else
                result.nCommonMessages+=1;
        }
        //pack destbloblstore.
        try {
            blobStore.pack();
        } catch (IOException e) {
            log.warn("Unable to pack blobstore: Serious error");
        }
        result.nFinalMessages = getAllDocs().size();
        result.nFinalAttachments = blobStore.uniqueBlobs.size();
        ///////////////////////Address book merging////////////////////////////////////////////////
        result.addressBookMergeResult = addressBook.merge(other.getAddressBook());
        ///////////////////////Label Manager merging///////////////////////////////////////////////
        result.labManagerMergeResult = labelManager.merge(other.getLabelManager());
        ///////////////////////Entity book merging/////////////////////////////////////////////////


        ////////////////////Lexicon merging///////////////////////////////////////////////////////
        //For merging lexicons copy those lexicon files from other's lexicon directory which are not
        //present in this archive's lexicon directory.Report the names of the files which are present
        //and the number of new files imported successfully from other archive.
        String otherLexDir = other.baseDir + File.separatorChar + LEXICONS_SUBDIR;
        String thisLexDir = baseDir + File.separatorChar + LEXICONS_SUBDIR;
        File otherlexDirFile = new File(otherLexDir);
        result.newLexicons = new LinkedHashSet<>();
        result.clashedLexicons = new LinkedHashSet<>();
        try {
            Map<String,Lexicon> collectionLexiconMap = createLexiconMap(baseDir);
            if (!otherlexDirFile.exists()) {
                log.warn("'lexicons' directory is missing from the accession");
            } else {
                for (File f : otherlexDirFile.listFiles(new Util.MyFilenameFilter(null, Lexicon.LEXICON_SUFFIX))) {
                    String name = Lexicon.lexiconNameFromFilename(f.getName());
                    if (!collectionLexiconMap.containsKey(name.toLowerCase())) {
                        //means collection does not have any lexicon of this name. copy it to thisLexDir and report it
                        Util.copy_file(f.getAbsolutePath(),thisLexDir+File.separatorChar+f.getName());
                        result.newLexicons.add(name);
                    }else{
                        //means there is a clash on lexicon names. Report it and dont' copy.
                        result.clashedLexicons.add(name);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Unable to merge lexicon map");
        }

        //save result for future reference.
        lastMergeResult = result;

    }
//    public void merge(Archive other) {
//        /* originalContentOnly */
//        other.getAllDocs().stream().filter(doc -> !this.containsDoc(doc)).forEach(doc -> this.addDoc(doc, other.getContents(doc, /* originalContentOnly */false)));
//
//        addressBook.merge(other.addressBook);
//        this.processingMetadata.merge(other.processingMetadata);
//    }

    private static Map<String, Lexicon> createLexiconMap(String baseDir) throws IOException {
        String lexDir = baseDir + File.separatorChar + LEXICONS_SUBDIR;
        Map<String, Lexicon> map = new LinkedHashMap<>();
        File lexDirFile = new File(lexDir);
        if (!lexDirFile.exists()) {
            log.warn("'lexicons' directory is missing from archive");
        } else {
            for (File f : lexDirFile.listFiles(new Util.MyFilenameFilter(null, Lexicon.LEXICON_SUFFIX))) {
                String name = Lexicon.lexiconNameFromFilename(f.getName());
                if (!map.containsKey(name)) {
                    map.put(name.toLowerCase(), new Lexicon(lexDir, name));
                }
            }
        }
        return map;
    }

    public Lexicon getLexicon(String lexName) {
        // lexicon map could be stale, re-read it
        try {
            lexiconMap = createLexiconMap(baseDir);
        } catch (Exception e) {
            Util.print_exception("Error trying to read list of lexicons", e, log);
        }
        return lexiconMap.get(lexName.toLowerCase());
    }

    public Set<String> getAvailableLexicons() {
        // lexicon map could be stale, re-read it
        try {
            lexiconMap = createLexiconMap(baseDir);
        } catch (Exception e) {
            Util.print_exception("Error trying to read list of lexicons", e, log);
        }
        if (lexiconMap == null)
            return new LinkedHashSet<>();
        return Collections.unmodifiableSet(lexiconMap.keySet());
    }

    /** returns an array (sorted by doc count for set labels.
     *  each element of the array is itself an array corresponding to the details for one label.
     *  important: labels with 0 count should not be returned. */
    public JSONArray getLabelCountsAsJson(Collection<Document> docs) {
        Map<String, Integer> labelIdToCount = new LinkedHashMap<>();

        for (Label label: getLabelManager().getAllLabels()) {
            labelIdToCount.put (label.getLabelID(), 0);
        }

        for (Document d: docs) {
            Set<String> labelIds = labelManager.getLabelIDs(d.getUniqueId());
            for (String labelId: labelIds) {
                Integer I = labelIdToCount.getOrDefault (labelId, 0);
                labelIdToCount.put (labelId, I+1);
            }
        }

        // sort by count
        List<Pair<String, Integer>> pairs = Util.sortMapByValue(labelIdToCount);

        // assemble the result json object
        int count = 0;
        JSONArray resultArray = new JSONArray();
        for (Pair<String, Integer> p: pairs) {
            String labelId = p.getFirst();

            JSONArray array = new JSONArray();

            Integer docCount = p.getSecond();
            Label label = labelManager.getLabel(labelId);
            array.put (0, labelId);
            array.put (1, label.getLabelName());
            array.put (2, label.getDescription());
            array.put (3, docCount);
            array.put (4, label.isSysLabel());
            String labelTypeDescription = LabelManager.LabType.RESTR_LAB.equals(label.getType()) ? "Restriction" : "General";

            if (LabelManager.LabType.RESTR_LAB.equals(label.getType()) && LabelManager.RestrictionType.RESTRICTED_FOR_YEARS.equals (label.getRestrictionType())) {
                labelTypeDescription += " for " + Util.pluralize(label.getRestrictedForYears(), "year");
            } else if (LabelManager.LabType.RESTR_LAB.equals(label.getType()) && LabelManager.RestrictionType.RESTRICTED_UNTIL.equals (label.getRestrictionType())) {
                long time = label.getRestrictedUntilTime();
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy");
                String description = sdf.format(new Date(time));
                labelTypeDescription += " until " + description;
            }
            array.put (5, labelTypeDescription);

            resultArray.put (count++, array);
        }

        return resultArray;
    }

    public void addStats(FetchStats as) {
        allStats.add(as);
    }

    public Collection<String> getDataErrors() {
        Collection<String> result = new LinkedHashSet<>();

        for (FetchStats as : allStats) {
            Collection<String> asErrors = as.dataErrors;
            if (asErrors != null)
                result.addAll(asErrors);
        }
        return result;
    }

    /**Replaces the document in the index with the supplied document*/
    public void updateDocument(org.apache.lucene.document.Document doc) {
        indexer.updateDocument(doc);
    }

    public void setupForWrite() throws IOException{
        indexer.setupForWrite();
    }

    public Span[] getOriginalNamesOfATypeInDoc(edu.stanford.muse.index.Document doc, short type) throws IOException{
        Span[] spans = getAllOriginalNamesInDoc(doc);
        List<Span> req = Arrays.stream(spans).filter(sp->sp.type==type).collect(Collectors.toList());
        return req.toArray(new Span[req.size()]);
    }

    private Span[] getAllOriginalNamesInDoc(edu.stanford.muse.index.Document doc) throws IOException{
        Span[] spans = getAllNamesInDoc(doc, true);
        String oc = getContents(doc, true);
        List<Span> req = Arrays.stream(spans).filter(sp->sp.end<oc.length()).collect(Collectors.toList());
        return req.toArray(new Span[req.size()]);
    }

    /**@return a list of names filtered to remove dictionary matches*/
    public Span[] getNamesOfATypeInDoc(edu.stanford.muse.index.Document d, boolean body, short type) throws IOException{
        return getNamesOfATypeInLuceneDoc(getLuceneDoc(d.getUniqueId()), body, type);
    }

    /**@return list of all names in the lucene doc without filtering dictionary words*/
    private static Span[] getNamesOfATypeInLuceneDoc(org.apache.lucene.document.Document ldoc, boolean body, short type) {
        Span[] allNames = NER.getNames(ldoc, body);
        List<Span> req = Arrays.stream(allNames).filter(s->type==s.type).collect(Collectors.toList());
        return req.toArray(new Span[req.size()]);
    }

    public Span[] getAllNamesInDoc(edu.stanford.muse.index.Document d, boolean body) throws IOException{
        return NER.getNames(d, body, this);
    }

    public static Span[] getAllNamesInLuceneDoc(org.apache.lucene.document.Document ldoc, boolean body){
        return NER.getNames(ldoc, body);
    }

    public Span[] getAllNamesMapToInDoc(edu.stanford.muse.index.Document d, boolean body, short coarseType) throws IOException{
        Span[] allNames = getAllNamesInDoc(d, body);
        List<Span> req = Arrays.stream(allNames).filter(n-> NEType.getCoarseType(n.type).getCode()==coarseType).collect(Collectors.toList());
        return req.toArray(new Span[req.size()]);
    }

    /**@return list of all email sources */
    public synchronized Set<String> getAllEmailSources() {
        if (allEmailSources == null) {
            allEmailSources = new LinkedHashSet<>();
            Collection<EmailDocument> docs = (Collection) getAllDocs();
            for (EmailDocument d : docs)
                if (!Util.nullOrEmpty(d.emailSource))
                    allEmailSources.add(d.emailSource);

        }
        return allEmailSources;
    }

    /**@return list of all email sources */
    public synchronized Set<String> getAllFolders() {
        if (allFolders == null) {
            allFolders = new LinkedHashSet<>();
            Collection<EmailDocument> docs = (Collection) getAllDocs();
            for (EmailDocument d : docs)
                if (!Util.nullOrEmpty(d.folderName))
                    allFolders.add(d.folderName);
        }
        return allFolders;
    }

    // invalidate the cache of all annotations. this should be called any time an annotation changes
    public void clearAllAnnotationsCache() {
        allAnnotations = null;
    }

    /**@return list of all email sources */
    public synchronized Set<String> getAllAnnotations() {
        if (allAnnotations == null) {
            allAnnotations = new LinkedHashSet<>();
            for (Document d : getAllDocs())
                if (!Util.nullOrEmpty(d.comment))
                   allAnnotations.add(d.comment);
        }
        return allAnnotations;
    }

    /**@return list of all email sources */
    public synchronized Set<String> getAllBlobNames() {
        if (allBlobNames == null) {
            allBlobNames = new LinkedHashSet<>();
            Collection<EmailDocument> docs = (Collection) getAllDocs();
            for (EmailDocument d : docs)
                if (!Util.nullOrEmpty(d.attachments)) {
                    List<Blob> blobs = d.attachments;
                    for (Blob b : blobs) {
                        if (!Util.nullOrEmpty(b.getName()))
                            allBlobNames.add(b.getName());
                    }
                }
        }
        return allBlobNames;
    }


    /**
     * Recognises names in the supplied text with OpenNLP NER
     * @Deprecated
     */
    @Deprecated public static Set<String> extractNamesOpenNLP(String text) throws Exception {
        List<Pair<String, Float>> pairs = edu.stanford.muse.index.NER.namesFromText(text);
        Set<String> names = new LinkedHashSet<>();
        for (Pair<String, ?> p : pairs)
            names.add(p.getFirst());

        return Util.scrubNames(names);
    }

    //maps locId (int) to docId (edu.stanford.muse.index.Document) using indexer storage structures
    public Integer getLDocIdForContentDocId(String docId){
        return indexer.contentDocIds.entrySet().stream().filter(e->docId.equals(e.getValue())).findAny().map(Map.Entry::getKey).orElse(0);
    }

    public String getDocIdForContentLDocId(Integer ldocId){
        return indexer.contentDocIds.get(ldocId);
    }

    public Integer getLDocIdForBlobDocId(String docId){
        return indexer.blobDocIds.entrySet().stream().filter(e->docId.equals(e.getValue())).findAny().map(Map.Entry::getKey).orElse(0);
    }

    public String getDocIdForBlobLDocId(Integer ldocId) {
        return indexer.blobDocIds.get(ldocId);
    }

    public static void main(String[] args) {
        try {
            String userDir = System.getProperty("user.home") + File.separator + "epadd-appraisal" + File.separator + "user";
            Archive archive = SimpleSessions.readArchiveIfPresent(userDir);
            List<Document> docs = archive.getAllDocs();
            int i=0;
            archive.assignThreadIds();
            NER.NERStats stats = new NER.NERStats();
            for(Document doc: docs) {
                EmailDocument ed = (EmailDocument) doc;
                stats.update(archive.getAllNamesInDoc(ed, true));
                System.out.println(Arrays.asList(archive.getAllNamesInDoc(ed, true)));
                if(i++>20)
                    break;
//                List<Document> threads = archive.docsWithThreadId(ed.threadID);
//                if(threads.size()>0){
//                    int numSent = 0;
//                    for(Document d: threads){
//                        EmailDocument thread = (EmailDocument)d;
//                        int sent = thread.sentOrReceived(archive.addressBook)&EmailDocument.SENT_MASK;
//                        if(sent>0)
//                            numSent++;
//                    }
//                    if(threads.size()!=numSent || threads.size()>2){
//                        System.err.println("Found a thread with "+numSent+" sent and "+threads.size()+" docs in a thread: "+ed.getSubject());
//                        break;
//                    }
//                    if(i%100 == 0)
//                        System.err.println("Scanned: "+i+" docs");
//                }
//                i++;
            }
            System.out.println(stats.counts);
            System.out.println(stats.all);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
