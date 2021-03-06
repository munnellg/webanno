/*
 * Copyright 2012
 * Ubiquitous Knowledge Processing (UKP) Lab and FG Language Technology
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.clarin.webanno.api.dao;

import static de.tudarmstadt.ukp.clarin.webanno.api.ProjectService.*;
import static de.tudarmstadt.ukp.clarin.webanno.api.WebAnnoConst.INITIAL_CAS_PSEUDO_USER;
import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.io.IOUtils.copyLarge;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

import javax.annotation.Resource;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;

import org.apache.commons.io.FileUtils;
import org.apache.uima.UIMAException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASException;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.apache.uima.util.CasCreationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import de.tudarmstadt.ukp.clarin.webanno.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.clarin.webanno.api.CasStorageService;
import de.tudarmstadt.ukp.clarin.webanno.api.DocumentService;
import de.tudarmstadt.ukp.clarin.webanno.api.ImportExportService;
import de.tudarmstadt.ukp.clarin.webanno.api.ProjectLifecycleAware;
import de.tudarmstadt.ukp.clarin.webanno.api.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.api.WebAnnoConst;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocumentState;
import de.tudarmstadt.ukp.clarin.webanno.model.Mode;
import de.tudarmstadt.ukp.clarin.webanno.model.PermissionLevel;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocumentStateTransition;
import de.tudarmstadt.ukp.clarin.webanno.model.User;
import de.tudarmstadt.ukp.clarin.webanno.support.logging.Logging;

public class DocumentServiceImpl
    implements DocumentService, InitializingBean, ProjectLifecycleAware
{
    private final Logger log = LoggerFactory.getLogger(getClass());

    @PersistenceContext
    private EntityManager entityManager;

    @Resource(name = "annotationService")
    private AnnotationSchemaService annotationService;
    
    @Resource(name = "userRepository")
    private UserDao userRepository;

    @Resource(name = "casStorageService")
    private CasStorageService casStorageService;

    @Resource(name = "importExportService")
    private ImportExportService importExportService;

    @Value(value = "${repository.path}")
    private File dir;

    @Override
    public void afterPropertiesSet()
        throws Exception
    {
        log.info("Repository: " + dir);
    }
    
    @Override
    public File getDir()
    {
        return dir;
    }
    
    @Override
    public File getDocumentFolder(SourceDocument aDocument)
        throws IOException
    {
        File sourceDocFolder = new File(dir, PROJECT + aDocument.getProject().getId() + DOCUMENT
                + aDocument.getId() + SOURCE);
        FileUtils.forceMkdir(sourceDocFolder);
        return sourceDocFolder;
    }

    @Override
    @Transactional
    public void createSourceDocument(SourceDocument aDocument)
        throws IOException
    {
        if (aDocument.getId() == 0) {
            entityManager.persist(aDocument);
        }
        else {
            entityManager.merge(aDocument);
        }
    }

    @Override
    @Transactional
    public boolean existsAnnotationDocument(SourceDocument aDocument, User aUser)
    {
        try {
            entityManager
                    .createQuery(
                            "FROM AnnotationDocument WHERE project = :project "
                                    + " AND document = :document AND user = :user",
                            AnnotationDocument.class)
                    .setParameter("project", aDocument.getProject())
                    .setParameter("document", aDocument).setParameter("user", aUser.getUsername())
                    .getSingleResult();
            return true;
        }
        catch (NoResultException ex) {
            return false;
        }
    }

    @Override
    @Transactional
    public void createAnnotationDocument(AnnotationDocument aAnnotationDocument)
    {
        if (aAnnotationDocument.getId() == 0) {
            entityManager.persist(aAnnotationDocument);
        }
        else {
            entityManager.merge(aAnnotationDocument);
        }
        
        try (MDC.MDCCloseable closable = MDC.putCloseable(Logging.KEY_PROJECT_ID,
                String.valueOf(aAnnotationDocument.getProject().getId()))) {
            log.info(
                    "Created annotation document [{}] for user [{}] for source document [{}]({}) "
                    + "in project [{}]({})",
                    aAnnotationDocument.getId(), aAnnotationDocument.getUser(), 
                    aAnnotationDocument.getDocument().getName(),
                    aAnnotationDocument.getDocument().getId(),
                    aAnnotationDocument.getProject().getName(),
                    aAnnotationDocument.getProject().getId());
        }
    }

    @Override
    @Transactional
    public boolean existsCas(SourceDocument aSourceDocument, String aUsername)
        throws IOException
    {
        return new File(casStorageService.getAnnotationFolder(aSourceDocument), aUsername + ".ser")
                .exists();
    }

    @Override
    @Transactional
    public boolean existsSourceDocument(Project aProject, String aFileName)
    {
        try {
            entityManager
                    .createQuery(
                            "FROM SourceDocument WHERE project = :project AND " + "name =:name ",
                            SourceDocument.class).setParameter("project", aProject)
                    .setParameter("name", aFileName).getSingleResult();
            return true;
        }
        catch (NoResultException ex) {
            return false;
        }
    }

    @Override
    public File getSourceDocumentFile(SourceDocument aDocument)
    {
        File documentUri = new File(dir.getAbsolutePath() + PROJECT
                + aDocument.getProject().getId() + DOCUMENT + aDocument.getId() + SOURCE);
        return new File(documentUri, aDocument.getName());
    }

    @Override
    public File getCasFile(SourceDocument aDocument, String aUser)
    {
        File documentUri = new File(dir.getAbsolutePath() + PROJECT
                + aDocument.getProject().getId() + DOCUMENT + aDocument.getId() + ANNOTATION);
        return new File(documentUri, aUser + ".ser");
    }
    
    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public AnnotationDocument createOrGetAnnotationDocument(SourceDocument aDocument, User aUser)
    {
        // Check if there is an annotation document entry in the database. If there is none,
        // create one.
        AnnotationDocument annotationDocument = null;
        if (!existsAnnotationDocument(aDocument, aUser)) {
            annotationDocument = new AnnotationDocument();
            annotationDocument.setDocument(aDocument);
            annotationDocument.setName(aDocument.getName());
            annotationDocument.setUser(aUser.getUsername());
            annotationDocument.setProject(aDocument.getProject());
            createAnnotationDocument(annotationDocument);
        }
        else {
            annotationDocument = getAnnotationDocument(aDocument, aUser);
        }

        return annotationDocument;
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public AnnotationDocument getAnnotationDocument(SourceDocument aDocument, User aUser)
    {
        return entityManager
                .createQuery(
                        "FROM AnnotationDocument WHERE document = :document AND " + "user =:user"
                                + " AND project = :project", AnnotationDocument.class)
                .setParameter("document", aDocument).setParameter("user", aUser.getUsername())
                .setParameter("project", aDocument.getProject()).getSingleResult();
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public SourceDocument getSourceDocument(Project aProject, String aDocumentName)
    {
        return entityManager
                .createQuery("FROM SourceDocument WHERE name = :name AND project =:project",
                        SourceDocument.class).setParameter("name", aDocumentName)
                .setParameter("project", aProject).getSingleResult();
    }
    
    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public SourceDocument getSourceDocument(long aProjectId, long aSourceDocId)
    {              
        return entityManager.createQuery("FROM SourceDocument WHERE id = :docid AND project.id =:pid", SourceDocument.class)
                .setParameter("docid", aSourceDocId)
                .setParameter("pid", aProjectId).getSingleResult();
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public boolean existsFinishedAnnotation(SourceDocument aDocument)
    {
        List<AnnotationDocument> annotationDocuments = entityManager
                .createQuery("FROM AnnotationDocument WHERE document = :document",
                        AnnotationDocument.class).setParameter("document", aDocument)
                .getResultList();
        for (AnnotationDocument annotationDocument : annotationDocuments) {
            if (annotationDocument.getState().equals(AnnotationDocumentState.FINISHED)) {
                return true;
            }
        }

        return false;
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public boolean existsFinishedAnnotation(Project aProject)
    {
        for (SourceDocument document : listSourceDocuments(aProject)) {
            List<AnnotationDocument> annotationDocuments = entityManager
                    .createQuery("FROM AnnotationDocument WHERE document = :document",
                            AnnotationDocument.class).setParameter("document", document)
                    .getResultList();
            for (AnnotationDocument annotationDocument : annotationDocuments) {
                if (annotationDocument.getState().equals(AnnotationDocumentState.FINISHED)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public List<AnnotationDocument> listFinishedAnnotationDocuments(Project aProject)
    {
        // Get all annotators in the project
        List<String> users = getAllAnnotators(aProject);
        // Bail out already. HQL doesn't seem to like queries with an empty
        // parameter right of "in"
        if (users.isEmpty()) {
            return new ArrayList<AnnotationDocument>();
        }

        return entityManager
                .createQuery(
                        "FROM AnnotationDocument WHERE project = :project AND state = :state"
                                + " AND user in (:users)", AnnotationDocument.class)
                .setParameter("project", aProject).setParameter("users", users)
                .setParameter("state", AnnotationDocumentState.FINISHED).getResultList();
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public List<AnnotationDocument> listAllAnnotationDocuments(SourceDocument aSourceDocument)
    {
        return entityManager
                .createQuery(
                        "FROM AnnotationDocument WHERE project = :project AND document = :document",
                        AnnotationDocument.class)
                .setParameter("project", aSourceDocument.getProject())
                .setParameter("document", aSourceDocument).getResultList();
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public List<SourceDocument> listSourceDocuments(Project aProject)
    {
        List<SourceDocument> sourceDocuments = entityManager
                .createQuery("FROM SourceDocument where project =:project ORDER BY name ASC", SourceDocument.class)
                .setParameter("project", aProject).getResultList();
        List<SourceDocument> tabSepDocuments = new ArrayList<SourceDocument>();
        for (SourceDocument sourceDocument : sourceDocuments) {
            if (sourceDocument.getFormat().equals(WebAnnoConst.TAB_SEP)) {
                tabSepDocuments.add(sourceDocument);
            }
        }
        sourceDocuments.removeAll(tabSepDocuments);
        return sourceDocuments;
    }

    @Override
    @Transactional
    public void removeSourceDocument(SourceDocument aDocument)
        throws IOException
    {
        for (AnnotationDocument annotationDocument : listAllAnnotationDocuments(aDocument)) {
            removeAnnotationDocument(annotationDocument);
        }
        
        entityManager.remove(aDocument);

        String path = dir.getAbsolutePath() + PROJECT + aDocument.getProject().getId() + DOCUMENT
                + aDocument.getId();
        // remove from file both source and related annotation file
        if (new File(path).exists()) {
            FileUtils.forceDelete(new File(path));
        }

        try (MDC.MDCCloseable closable = MDC.putCloseable(Logging.KEY_PROJECT_ID,
                String.valueOf(aDocument.getProject().getId()))) {
            Project project = aDocument.getProject();
            log.info("Removed source document [{}]({}) from project [{}]({})", aDocument.getName(),
                    aDocument.getId(), project.getName(), project.getId());
        }
    }

    @Override
    @Transactional
    public void removeAnnotationDocument(AnnotationDocument aAnnotationDocument)
    {
        entityManager.remove(aAnnotationDocument);
    }

    @Override
    @Transactional
    public void uploadSourceDocument(File aFile, SourceDocument aDocument)
        throws IOException
    {
        // Create the metadata record - this also assigns the ID to the document
        createSourceDocument(aDocument);

        // Copy the original file into the repository
        File targetFile = getSourceDocumentFile(aDocument);
        FileUtils.forceMkdir(targetFile.getParentFile());
        FileUtils.copyFile(aFile, targetFile);
        
        // Check if the file has a valid format / can be converted without error
        // This requires that the document ID has already been assigned
        try {
            createInitialCas(aDocument);
        }
        catch (Exception e) {
            FileUtils.forceDelete(targetFile);
            removeSourceDocument(aDocument);
            throw new IOException(e.getMessage(), e);
        }

        try (MDC.MDCCloseable closable = MDC.putCloseable(Logging.KEY_PROJECT_ID,
                String.valueOf(aDocument.getProject().getId()))) {
            Project project = aDocument.getProject();
            log.info("Imported source document [{}]({}) to project [{}]({})", 
                    aDocument.getName(), aDocument.getId(), project.getName(), project.getId());
        }
    }

    @Override
    @Transactional
    @Deprecated
    public void uploadSourceDocument(InputStream aIs, SourceDocument aDocument)
        throws IOException
    {
        File targetFile = getSourceDocumentFile(aDocument);
        FileUtils.forceMkdir(targetFile.getParentFile());

        OutputStream os = null;
        try {
            os = new FileOutputStream(targetFile);
            copyLarge(aIs, os);
        }
        finally {
            closeQuietly(os);
            closeQuietly(aIs);
        }

        try (MDC.MDCCloseable closable = MDC.putCloseable(Logging.KEY_PROJECT_ID,
                String.valueOf(aDocument.getProject().getId()))) {
            Project project = aDocument.getProject();
            log.info("Imported source document [{}]({}) to project [{}]({})", 
                    aDocument.getName(), aDocument.getId(), project.getName(), project.getId());
        }
    }
    
    @Override
    public boolean existsInitialCas(SourceDocument aDocument)
        throws IOException
    {
        return existsCas(aDocument, INITIAL_CAS_PSEUDO_USER);
    }
    
    @Override
    public JCas createInitialCas(SourceDocument aDocument)
        throws UIMAException, IOException, ClassNotFoundException
    {
        // Normally, the initial CAS should be created on document import, but after
        // adding this feature, the existing projects do not yet have initial CASes, so
        // we create them here lazily
        JCas jcas = importExportService.importCasFromFile(getSourceDocumentFile(aDocument),
                aDocument.getProject(), aDocument.getFormat());
        casStorageService.analyzeAndRepair(aDocument, INITIAL_CAS_PSEUDO_USER, jcas.getCas());
        CasPersistenceUtils.writeSerializedCas(jcas,
                getCasFile(aDocument, INITIAL_CAS_PSEUDO_USER));
        
        return jcas;
    }
    
    @Override
    public JCas readInitialCas(SourceDocument aDocument)
        throws CASException, ResourceInitializationException, IOException
    {
        JCas jcas = CasCreationUtils.createCas((TypeSystemDescription) null, null, null).getJCas();
        
        CasPersistenceUtils.readSerializedCas(jcas, getCasFile(aDocument, INITIAL_CAS_PSEUDO_USER));
        
        casStorageService.analyzeAndRepair(aDocument, INITIAL_CAS_PSEUDO_USER, jcas.getCas());
        
        return jcas;
    }

    @Override
    public JCas createOrReadInitialCas(SourceDocument aDocument)
        throws IOException, UIMAException, ClassNotFoundException
    {
        if (existsInitialCas(aDocument)) {
            return readInitialCas(aDocument);
        }
        else {
            return createInitialCas(aDocument);
        }
    }
    
    @Override
    @Transactional
    @Deprecated
    public JCas readAnnotationCas(SourceDocument aDocument, User aUser)
        throws IOException
    {
        // Change the state of the source document to in progress
        aDocument.setState(SourceDocumentStateTransition
                .transition(SourceDocumentStateTransition.NEW_TO_ANNOTATION_IN_PROGRESS));

        // Check if there is an annotation document entry in the database. If there is none,
        // create one.
        AnnotationDocument annotationDocument = createOrGetAnnotationDocument(aDocument, aUser);

        return readAnnotationCas(annotationDocument);
    }
    
    @Override
    @Transactional
    public JCas readAnnotationCas(AnnotationDocument aAnnotationDocument)
        throws IOException
    {
        // If there is no CAS yet for the annotation document, create one.
        JCas jcas = null;
        SourceDocument aDocument = aAnnotationDocument.getDocument();
        String user = aAnnotationDocument.getUser();
        if (!existsCas(aAnnotationDocument.getDocument(), user)) {
            // Convert the source file into an annotation CAS
            try {
                if (!existsInitialCas(aDocument)) {
                    jcas = createInitialCas(aDocument);
                }

                // Ok, so at this point, we either have the lazily converted CAS already loaded
                // or we know that we can load the existing initial CAS.
                if (jcas == null) {
                    jcas = readInitialCas(aDocument);
                }
            }
            catch (Exception e) {
                log.error("The reader for format [" + aDocument.getFormat()
                        + "] is unable to digest data", e);
                throw new IOException("The reader for format [" + aDocument.getFormat()
                        + "] is unable to digest data" + e.getMessage());
            }
            casStorageService.writeCas(aDocument, jcas, user);
        }
        else {
            // Read existing CAS
            // We intentionally do not upgrade the CAS here because in general the IDs
            // must remain stable. If an upgrade is required the caller should do it
            jcas = casStorageService.readCas(aDocument, user);
        }

        return jcas;
    }
    
    @Override
    @Transactional
    public void writeAnnotationCas(JCas aJcas, SourceDocument aDocument, User aUser,
            boolean aUpdateTimestamp)
        throws IOException
    {
        casStorageService.writeCas(aDocument, aJcas, aUser.getUsername());
        if (aUpdateTimestamp) {
            AnnotationDocument annotationDocument = getAnnotationDocument(aDocument, aUser);
            annotationDocument.setSentenceAccessed(aDocument.getSentenceAccessed());
            annotationDocument.setTimestamp(new Timestamp(new Date().getTime()));
            annotationDocument.setState(AnnotationDocumentState.IN_PROGRESS);
            entityManager.merge(annotationDocument);
        }
    }

    @Override
    @Deprecated
    public void upgradeCasAndSave(SourceDocument aDocument, Mode aMode, String aUsername)
        throws IOException
    {
        User user = userRepository.get(aUsername);
        if (existsAnnotationDocument(aDocument, user)) {
            log.debug("Upgrading annotation document [" + aDocument.getName() + "] " + "with ID ["
                    + aDocument.getId() + "] in project ID [" + aDocument.getProject().getId()
                    + "] for user [" + aUsername + "] in mode [" + aMode + "]");
            // DebugUtils.smallStack();

            AnnotationDocument annotationDocument = getAnnotationDocument(aDocument, user);
            try {
                CAS cas = readAnnotationCas(annotationDocument).getCas();
                upgradeCas(cas, annotationDocument);
                writeAnnotationCas(cas.getJCas(), annotationDocument.getDocument(), user, false);

                // This is no longer needed because it is handled on the respective pages.
//                if (aMode.equals(Mode.ANNOTATION)) {
//                    // In this case we only need to upgrade to annotation document
//                }
//                else if (aMode.equals(Mode.AUTOMATION) || aMode.equals(Mode.CORRECTION)) {
//                    CAS corrCas = readCorrectionCas(aDocument).getCas();
//                    upgradeCas(corrCas, annotationDocument);
//                    writeCorrectionCas(corrCas.getJCas(), aDocument, user, false);
//                }
//                else {
//                    CAS curCas = readCurationCas(aDocument).getCas();
//                    upgradeCas(curCas, annotationDocument);
//                    writeCurationCas(curCas.getJCas(), aDocument, user);
//                }

            }
            catch (Exception e) {
                // no need to catch, it is acceptable that no curation document
                // exists to be upgraded while there are annotation documents
            }
            
            try (MDC.MDCCloseable closable = MDC.putCloseable(
                    Logging.KEY_PROJECT_ID,
                    String.valueOf(aDocument.getProject().getId()))) {
                Project project = aDocument.getProject();
                log.info(
                        "Upgraded annotations of user [{}] for "
                                + "document [{}]({}) in project [{}]({}) in mode [{}]",
                        user.getUsername(), aDocument.getName(), aDocument.getId(),
                        project.getName(), project.getId(), aMode);
            }
        }
    }

    @Override
    public void upgradeCas(CAS aCas, AnnotationDocument aAnnotationDocument)
        throws UIMAException, IOException
    {
        annotationService.upgradeCas(aCas, aAnnotationDocument.getDocument(),
                aAnnotationDocument.getUser());
    }
    
    /**
     * Return true if there exist at least one annotation document FINISHED for annotation for this
     * {@link SourceDocument}
     *
     * @param aSourceDocument
     *            the source document.
     * @param aProject
     *            the project.
     * @return if a finished document exists.
     */
    @Override
    public boolean existFinishedDocument(SourceDocument aSourceDocument, Project aProject)
    {
        List<de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument> annotationDocuments = listAnnotationDocuments(
                aSourceDocument);
        boolean finishedAnnotationDocumentExist = false;
        for (de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument annotationDocument : annotationDocuments) {
            if (annotationDocument.getState().equals(AnnotationDocumentState.FINISHED)) {
                finishedAnnotationDocumentExist = true;
                break;
            }
        }
        return finishedAnnotationDocumentExist;
    }
    
    @Override
    public Map<SourceDocument, AnnotationDocument> listAnnotatableDocuments(Project aProject,
            User aUser)
    {
        // First get the source documents
        List<SourceDocument> sourceDocuments = entityManager
                .createQuery(
                        "FROM SourceDocument " +
                        "WHERE project = (:project) AND trainingDocument = false",
                        SourceDocument.class)
                .setParameter("project", aProject)
                .getResultList();

        // Next check if we have any annotation document records that state that the document
        // is ignored for the given user
        List<AnnotationDocument> annotationDocuments = entityManager
                .createQuery(
                        "FROM AnnotationDocument " +
                        "WHERE user = (:username) AND state != (:state) AND project = (:project)",
                        AnnotationDocument.class)
                .setParameter("username", aUser.getUsername())
                .setParameter("project", aProject)
                .setParameter("state", AnnotationDocumentState.IGNORE)
                .getResultList();

        // First we add all the source documents for which we have an annotation document
        Map<SourceDocument, AnnotationDocument> map = new TreeMap<>(SourceDocument.NAME_COMPARATOR);
        for (AnnotationDocument adoc : annotationDocuments) {
            map.put(adoc.getDocument(), adoc);
        }

        // Then we also add all the source documents for which we do not have an annoation document
        for (SourceDocument doc : sourceDocuments) {
            if (!map.containsKey(doc)) {
                map.put(doc, null);
            }
        }

        return map;
    }
    
    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public boolean isAnnotationFinished(SourceDocument aDocument, User aUser)
    {
        try {
            AnnotationDocument annotationDocument = entityManager
                    .createQuery(
                            "FROM AnnotationDocument WHERE document = :document AND "
                                    + "user =:user", AnnotationDocument.class)
                    .setParameter("document", aDocument).setParameter("user", aUser.getUsername())
                    .getSingleResult();
            if (annotationDocument.getState().equals(AnnotationDocumentState.FINISHED)) {
                return true;
            }
            else {
                return false;
            }
        }
        // User even didn't start annotating
        catch (NoResultException e) {
            return false;
        }
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public List<AnnotationDocument> listAnnotationDocuments(SourceDocument aDocument)
    {
        // Get all annotators in the project
        List<String> users = getAllAnnotators(aDocument.getProject());
        // Bail out already. HQL doesn't seem to like queries with an empty
        // parameter right of "in"
        if (users.isEmpty()) {
            return new ArrayList<AnnotationDocument>();
        }

        return entityManager
                .createQuery(
                        "FROM AnnotationDocument WHERE project = :project AND document = :document "
                                + "AND user in (:users)", AnnotationDocument.class)
                .setParameter("project", aDocument.getProject()).setParameter("users", users)
                .setParameter("document", aDocument).getResultList();
    }
    
    @Override
    public List<AnnotationDocument> listAnnotationDocuments(Project aProject, User aUser)
    {
        return entityManager
                .createQuery("FROM AnnotationDocument WHERE project = :project AND user = :user",
                        AnnotationDocument.class)
                .setParameter("project", aProject).setParameter("user", aUser.getUsername())
                .getResultList();
    }

    @Override
    public int numberOfExpectedAnnotationDocuments(Project aProject)
    {

        // Get all annotators in the project
        List<String> users = getAllAnnotators(aProject);
        // Bail out already. HQL doesn't seem to like queries with an empty
        // parameter right of "in"
        if (users.isEmpty()) {
            return 0;
        }

        int ignored = 0;
        List<AnnotationDocument> annotationDocuments = entityManager
                .createQuery(
                        "FROM AnnotationDocument WHERE project = :project AND user in (:users)",
                        AnnotationDocument.class).setParameter("project", aProject)
                .setParameter("users", users).getResultList();
        for (AnnotationDocument annotationDocument : annotationDocuments) {
            if (annotationDocument.getState().equals(AnnotationDocumentState.IGNORE)) {
                ignored++;
            }
        }
        return listSourceDocuments(aProject).size() * users.size() - ignored;

    }
    
    private List<String> getAllAnnotators(Project aProject)
    {
        // Get all annotators in the project
        List<String> users = entityManager
                .createQuery(
                        "SELECT DISTINCT user FROM ProjectPermission WHERE project = :project "
                                + "AND level = :level", String.class)
                .setParameter("project", aProject).setParameter("level", PermissionLevel.USER)
                .getResultList();

        // check if the username is in the Users database (imported projects
        // might have username
        // in the ProjectPermission entry while it is not in the Users database
        List<String> notInUsers = new ArrayList<String>();
        for (String user : users) {
            if (!userRepository.exists(user)) {
                notInUsers.add(user);
            }
        }
        users.removeAll(notInUsers);

        return users;
    }
    
    @Override
    public void afterProjectCreate(Project aProject)
    {
        // Nothing to do
    }
    
    @Override
    public void beforeProjectRemove(Project aProject)
        throws IOException
    {
        for (SourceDocument document : listSourceDocuments(aProject)) {
            removeSourceDocument(document);
        }
    }

    @Override
    @Transactional
    public void onProjectImport(ZipFile aZip,
            de.tudarmstadt.ukp.clarin.webanno.model.export.Project aExportedProject,
            Project aProject)
        throws Exception
    {
        // Nothing at the moment
    }
}
