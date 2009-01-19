package uk.ac.manchester.cs.owl;

import org.semanticweb.owl.io.OWLOntologyInputSource;
import org.semanticweb.owl.io.OWLOntologyOutputTarget;
import org.semanticweb.owl.io.PhysicalURIInputSource;
import org.semanticweb.owl.io.PhysicalURIMappingNotFoundException;
import org.semanticweb.owl.model.*;

import java.net.URI;
import java.util.*;
import java.util.logging.Logger;
/*
 * Copyright (C) 2006, University of Manchester
 *
 * Modifications to the initial code base are copyright of their
 * respective authors, or their employers as appropriate.  Authorship
 * of the modifications may be determined from the ChangeLog placed at
 * the end of this file.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.

 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.

 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */


/**
 * Author: Matthew Horridge<br>
 * The University Of Manchester<br>
 * Bio-Health Informatics Group<br>
 * Date: 27-Oct-2006<br><br>
 */
public class OWLOntologyManagerImpl implements OWLOntologyManager, OWLOntologyFactory.OWLOntologyCreationHandler {

    private static final Logger logger = Logger.getLogger(OWLOntologyManagerImpl.class.getName());

    private boolean allowPhysicalURIsInImportsDeclarations = true;

    private Map<OWLOntologyID, OWLOntology> ontologiesByID;

    private Map<OWLOntologyID, URI> physicalURIsByID;

    private Map<OWLOntology, OWLOntologyFormat> ontologyFormatsByOntology;

    private List<OWLOntologyURIMapper> uriMappers;

    private List<OWLOntologyFactory> ontologyFactories;

    private List<OWLOntologyStorer> ontologyStorers;

    private boolean broadcastChanges;

    private int loadCount = 0;

    private int importsLoadCount = 0;

    private boolean silentMissingImportsHandling;

    private OWLDataFactory dataFactory;

    private Map<OWLOntology, Set<OWLOntology>> importsClosureCache;

    private OWLOntologyManagerProperties properties;

    private List<MissingImportListener> missingImportsListeners;

    private List<OWLOntologyLoaderListener> loaderListeners;

    private int autoGeneratedURICounter;


    public OWLOntologyManagerImpl(OWLDataFactory dataFactory) {
        this.dataFactory = dataFactory;
        properties = new OWLOntologyManagerProperties();
        ontologiesByID = new HashMap<OWLOntologyID, OWLOntology>();
        physicalURIsByID = new HashMap<OWLOntologyID, URI>();
        ontologyFormatsByOntology = new HashMap<OWLOntology, OWLOntologyFormat>();
        uriMappers = new ArrayList<OWLOntologyURIMapper>();
        ontologyFactories = new ArrayList<OWLOntologyFactory>();
        installDefaultURIMappers();
        installDefaultOntologyFactories();
        broadcastChanges = true;
        ontologyStorers = new ArrayList<OWLOntologyStorer>();
        importsClosureCache = new HashMap<OWLOntology, Set<OWLOntology>>();
        missingImportsListeners = new ArrayList<MissingImportListener>();
        loaderListeners = new ArrayList<OWLOntologyLoaderListener>();
        autoGeneratedURICounter = 0;
    }


    public OWLOntologyManagerProperties getProperties() {
        return properties;
    }


    public OWLDataFactory getOWLDataFactory() {
        return dataFactory;
    }


    public Set<OWLOntology> getOntologies() {
        return new HashSet<OWLOntology>(ontologiesByID.values());
    }


    public Set<OWLOntology> getOntologies(OWLAxiom axiom) {
        Set<OWLOntology> result = new HashSet<OWLOntology>(ontologiesByID.size());
        for (OWLOntology ont : getOntologies()) {
            if (ont.containsAxiom(axiom)) {
                result.add(ont);
            }
        }
        return result;
    }

    public Set<OWLOntology> getVersions(URI ontology) {
        throw new RuntimeException("TODO");
    }

    public boolean contains(OWLOntology ontology) {
        return ontologiesByID.containsValue(ontology);
    }


    public boolean contains(URI ontologyURI) {
        return contains(new OWLOntologyID(dataFactory.getIRI(ontologyURI)));
    }

    public boolean contains(OWLOntologyID id) {
        return ontologiesByID.containsKey(id);
    }

    public OWLOntology getOntology(URI ontologyURI) {
        OntologyIRIVersionIRIPair pair = new OntologyIRIVersionIRIPair(ontologyURI, null);
        return ontologiesByID.get(ontologyURI);
    }


    public OWLOntology getImportedOntology(OWLImportsDeclaration declaration) {
        OWLOntology importedOntology = ontologiesByID.get(declaration.getImportedOntologyURI());
        if (importedOntology != null) {
            return importedOntology;
        }
        if (allowPhysicalURIsInImportsDeclarations) {
            // The imports statement may have used a physical URI which
            // may not match with the ontology URI of the imported ontology
            // -- attempt to resolve this situation
            for (OWLOntologyID ontID : physicalURIsByID.keySet()) {
                URI physicalURI = physicalURIsByID.get(ontID);
                if (declaration.getImportedOntologyURI().equals(physicalURI)) {
                    // Declaration appears to use a physical URI! What a mess!
                    return ontologiesByID.get(ontID);
                }
            }
        }
        return null;
    }


    public Set<OWLOntology> getImports(OWLOntology ontology) {
        Set<OWLOntology> imports = new HashSet<OWLOntology>();
        for (OWLImportsDeclaration axiom : ontology.getImportsDeclarations()) {
            OWLOntology importedOntology = getImportedOntology(axiom);
            if (importedOntology != null) {
                imports.add(importedOntology);
            }
        }
        return imports;
    }


    public Set<OWLOntology> getImportsClosure(OWLOntology ontology) {
        Set<OWLOntology> ontologies = importsClosureCache.get(ontology);
        if (ontologies == null) {
            ontologies = new HashSet<OWLOntology>();
            getImportsClosure(ontology, ontologies);
            importsClosureCache.put(ontology, ontologies);
        }
        return Collections.unmodifiableSet(ontologies);
    }


    private void getImportsClosure(OWLOntology ontology, Set<OWLOntology> ontologies) {
        ontologies.add(ontology);
        for (OWLOntology ont : getImports(ontology)) {
            if (!ontologies.contains(ont)) {
                getImportsClosure(ont, ontologies);
            }
        }
    }


    public List<OWLOntology> getSortedImportsClosure(OWLOntology ontology) throws
            UnknownOWLOntologyException {
        List<OWLOntology> importsClosure = new ArrayList<OWLOntology>();
        getSortedImportsClosure(ontology, importsClosure, new HashSet<OWLOntology>());
        return importsClosure;
    }

    private void getSortedImportsClosure(OWLOntology ontology, List<OWLOntology> imports, Set<OWLOntology> marker) {
        if (!marker.contains(ontology)) {
            imports.add(ontology);
            marker.add(ontology);
            for (OWLOntology imported : getImports(ontology)) {
                getSortedImportsClosure(imported, imports, marker);
            }
        }
    }


    /**
     * Determines if a change is applicable.  A change may not be applicable
     * for a number of reasons.
     *
     * @param change The change to be tested.
     * @return <code>true</code> if the change is applicable,
     *         otherwise, <code>false</code>.
     */
    private boolean isChangeApplicable(OWLOntologyChange change) {
        if (!getProperties().isLoadAnnotationAxioms() && change instanceof AddAxiom) {
            if (change.getAxiom() instanceof OWLAnnotationAxiom) {
                return false;
            }
        }
        return true;
    }


    /**
     * Applies a change to an ontology and performs the necessary housekeeping
     * tasks.
     *
     * @param change The change to be applied.
     * @return A list of changes that were actually applied.
     * @throws OWLOntologyChangeException
     */
    private List<OWLOntologyChange> enactChangeApplication(OWLOntologyChange change) throws OWLOntologyChangeException {
        if (!isChangeApplicable(change)) {
            return Collections.emptyList();
        }
        OWLOntology ont = change.getOntology();
        if (!(ont instanceof OWLMutableOntology)) {
            throw new ImmutableOWLOntologyChangeException(change);
        }
        List<OWLOntologyChange> appliedChanges = ((OWLMutableOntology) ont).applyChange(change);
        checkForOntologyURIChange(change);
        checkForImportsChange(change);
        return appliedChanges;
    }


    public List<OWLOntologyChange> applyChanges(List<? extends OWLOntologyChange> changes) throws
            OWLOntologyChangeException {
        List<OWLOntologyChange> appliedChanges = new ArrayList<OWLOntologyChange>();
        for (OWLOntologyChange change : changes) {
            appliedChanges.addAll(enactChangeApplication(change));
        }
        broadcastChanges(changes);
        return appliedChanges;
        // Another option here is to sort the changes in to multiple lists, with one
        // list per ontology.  In this case we could apply a list of changes to each
        // ontology rather than applying changes one-by-one.
    }


    public List<OWLOntologyChange> addAxiom(OWLOntology ont, OWLAxiom axiom) throws OWLOntologyChangeException {
        return addAxioms(ont, Collections.singleton(axiom));
    }


    public List<OWLOntologyChange> addAxioms(OWLOntology ont, Set<? extends OWLAxiom> axioms) throws OWLOntologyChangeException {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>(axioms.size());
        // Optimisation - Precheck that the ontology is an immutable ontology.
        if (ont instanceof OWLMutableOntology) {
            for (OWLAxiom ax : axioms) {
                // Further optimisation - precheck that the axiom isn't in the ontology.
                if (!ont.containsAxiom(ax)) {
                    AddAxiom addAx = new AddAxiom(ont, ax);
                    changes.addAll(enactChangeApplication(addAx));
                }
            }
        }
        broadcastChanges(changes);
        return changes;
    }

    public List<OWLOntologyChange> applyChange(OWLOntologyChange change) throws OWLOntologyChangeException {
        List<OWLOntologyChange> changes = enactChangeApplication(change);
        broadcastChanges(changes);
        return changes;
    }


    private void checkForImportsChange(OWLOntologyChange change) {
        if (change.isAxiomChange() && change.getAxiom() instanceof OWLImportsDeclaration) {
            resetImportsClosureCache();
        }
    }


    private void checkForOntologyURIChange(OWLOntologyChange change) {
        if (change instanceof SetOntologyURI) {
            SetOntologyURI setURI = (SetOntologyURI) change;
            renameOntology(setURI.getOriginalOntologyID());
            resetImportsClosureCache();
        }
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Methods to create, load and reload ontologies
    //
    /////////////////////////////////////////////////////////////////////////////////////////////////////////////


    public void ontologyCreated(OWLOntology ontology) {
        // This method is called when a factory that we have asked to create or
        // load an ontology has created the ontology.  We add the ontology to the
        // set of loaded ontologies.
        addOntology(ontology);
    }


    public void setOntologyFormat(OWLOntology ontology, OWLOntologyFormat format) {
        ontologyFormatsByOntology.put(ontology, format);
    }


    public OWLOntologyFormat getOntologyFormat(OWLOntology ontology) {
        return ontologyFormatsByOntology.get(ontology);
    }

    private IRI toIRI(URI uri) {
        return dataFactory.getIRI(uri);
    }

    public OWLOntology createOntology() throws OWLOntologyCreationException {
        // Brand new ontology without a URI
        return createOntology(new OWLOntologyID());
    }

    public OWLOntology createOntology(URI ontologyURI) throws OWLOntologyCreationException {
        return createOntology(new OWLOntologyID(toIRI(ontologyURI)));
    }

    public OWLOntology createOntology(URI ontologyURI, URI versionURI) throws OWLOntologyCreationException {
        return createOntology(new OWLOntologyID(toIRI(ontologyURI), toIRI(versionURI)));
    }

    private OWLOntology createOntology(OWLOntologyID ontologyID) throws OWLOntologyCreationException {
        OWLOntology ontology = ontologiesByID.get(ontologyID);
        if (ontology != null) {
            return ontology;
        }
        URI physicalURI = getPhysicalURIFromOntologyURI(ontologyID.getOntologyIRI().toURI(), false);
        for (OWLOntologyFactory factory : ontologyFactories) {
            if (factory.canCreateFromPhysicalURI(physicalURI)) {
                physicalURIsByID.put(ontologyID, physicalURI);
                return factory.createOWLOntology(ontologyID, physicalURI, this);
            }
        }
        throw new OWLOntologyFactoryNotFoundException(physicalURI);
    }

    public OWLOntology createOntology(URI ontologyURI, Set<OWLOntology> ontologies) throws
            OWLOntologyCreationException,
            OWLOntologyChangeException {
        return createOntology(ontologyURI, ontologies, false);
    }


    public OWLOntology createOntology(URI ontologyURI, Set<OWLOntology> ontologies, boolean copyLogicalAxiomsOnly) throws
            OWLOntologyCreationException,
            OWLOntologyChangeException {
        if (contains(ontologyURI)) {
            throw new OWLOntologyAlreadyExistsException(new OWLOntologyID(dataFactory.getIRI(ontologyURI)));
        }
        OWLOntology ont = createOntology(ontologyURI);
        Set<OWLAxiom> axioms = new HashSet<OWLAxiom>();
        for (OWLOntology ontology : ontologies) {
            if (copyLogicalAxiomsOnly) {
                axioms.addAll(ontology.getLogicalAxioms());
            } else {
                axioms.addAll(ontology.getAxioms());
            }
        }
        addAxioms(ont, axioms);
        return ont;
    }


    public OWLOntology createOntology(Set<OWLAxiom> axioms, URI uri) throws OWLOntologyCreationException,
            OWLOntologyChangeException {
        if (contains(uri)) {
            throw new OWLOntologyAlreadyExistsException(new OWLOntologyID(toIRI(uri)));
        }
        OWLOntology ont = createOntology(uri);
        addAxioms(ont, axioms);
        return ont;
    }


    public OWLOntology createOntology(Set<OWLAxiom> axioms) throws OWLOntologyCreationException,
            OWLOntologyChangeException {
        return createOntology(axioms, getNextAutoGeneratedURI());
    }

    protected URI getNextAutoGeneratedURI() {
        autoGeneratedURICounter = autoGeneratedURICounter + 1;
        return URI.create("owlapi:ontology:ont" + autoGeneratedURICounter);
    }

    public OWLOntology loadOntology(URI ontologyURI) throws OWLOntologyCreationException {
        String frag = ontologyURI.getFragment();
        if (frag != null && frag.length() == 0) {
            // Empty fragment - should we strip this out???
        }
        OWLOntology ontology = ontologiesByID.get(ontologyURI);
        if (ontology != null) {
            return ontology;
        }
        URI physicalURI = getPhysicalURIFromOntologyURI(ontologyURI, true);
        // The ontology might be being loaded, but its logical URI might
        // not have been set (as is probably the case with RDF/XML!)
        if (physicalURI != null) {
            ontology = getOntology(physicalURI);
            if (ontology != null) {
                return ontology;
            }
        } else {
            // Nothing we can do here.  We can't get a physical URI to load
            // the ontology from.
            throw new PhysicalURIMappingNotFoundException(ontologyURI);
        }
        return loadOntology(ontologyURI, new PhysicalURIInputSource(physicalURI));
    }


    public OWLOntology loadOntologyFromPhysicalURI(URI uri) throws OWLOntologyCreationException {
        // Ontology URI not known in advance
        return loadOntology(null, new PhysicalURIInputSource(uri));
    }

    public OWLOntology loadOntology(OWLOntologyInputSource inputSource) throws OWLOntologyCreationException {
        // Ontology URI not known in advance
        return loadOntology(null, inputSource);
    }


    /**
     * This is the method that all the other load method delegate to.
     *
     * @param ontologyURI The URI of the ontology to be loaded.  This is only used to
     *                    report to listeners and may be <code>null</code>
     * @param inputSource The input source that specifies where the ontology should be loaded from.
     * @return The ontology that was loaded.
     * @throws OWLOntologyCreationException If the ontology could not be loaded.
     */
    protected OWLOntology loadOntology(URI ontologyURI, OWLOntologyInputSource inputSource) throws OWLOntologyCreationException {
        if (loadCount != importsLoadCount) {
            System.err.println("Runtime Warning: Parsers should load imported ontologies using the makeImportLoadRequest method.");
        }
        fireStartedLoadingEvent(ontologyURI, inputSource.getPhysicalURI(), loadCount > 0);
        loadCount++;
        broadcastChanges = false;
        OWLOntologyCreationException ex = null;
        URI uriOfLoadedOntology = ontologyURI;
        try {
            for (OWLOntologyFactory factory : ontologyFactories) {
                if (factory.canLoad(inputSource)) {
                    // Note - there is no need to add the ontology here, because it will be added
                    // when the ontology is created.
                    OWLOntology ontology = factory.loadOWLOntology(inputSource, this);
                    uriOfLoadedOntology = ontology.getURI();
                    // Store the ontology to physical URI mapping
                    physicalURIsByID.put(ontology.getOntologyID(), inputSource.getPhysicalURI());
                    return ontology;
                }
            }
        }
        catch (OWLOntologyCreationException e) {
            ex = e;
            throw e;
        }
        finally {
            loadCount--;
            if (loadCount == 0) {
                broadcastChanges = true;
                // Completed loading ontology and imports
            }
            fireFinishedLoadingEvent(uriOfLoadedOntology, inputSource.getPhysicalURI(), loadCount > 0, ex);
        }
        throw new OWLOntologyFactoryNotFoundException(inputSource.getPhysicalURI());
    }


    public void removeOntology(OWLOntology ontology) {
        ontologiesByID.remove(ontology.getOntologyID());
        ontologyFormatsByOntology.remove(ontology);
        physicalURIsByID.remove(ontology.getOntologyID());
        resetImportsClosureCache();
    }

    private void addOntology(OWLOntology ont) {
        ontologiesByID.put(ont.getOntologyID(), ont);
    }


    public URI getPhysicalURIForOntology(OWLOntology ontology) throws UnknownOWLOntologyException {
        URI physicalURI = physicalURIsByID.get(ontology.getOntologyID());
        if (physicalURI == null) {
            throw new UnknownOWLOntologyException(ontology.getURI());
        }
        return physicalURI;
    }


    public void setPhysicalURIForOntology(OWLOntology ontology, URI physicalURI) throws UnknownOWLOntologyException {
        if (!ontologiesByID.containsValue(ontology)) {
            throw new UnknownOWLOntologyException(ontology.getURI());
        }
        physicalURIsByID.put(ontology.getOntologyID(), physicalURI);
    }


    /**
     * Handles a rename of an ontology.  This method should only be called *after* the change has been applied
     *
     * @param oldID The original ID of the ontology
     */
    private void renameOntology(OWLOntologyID oldID) {
        OWLOntology ont = ontologiesByID.get(oldID);
        if (ont == null) {
            // Nothing to rename!
            return;
        }
        ontologiesByID.remove(oldID);
        ontologiesByID.put(ont.getOntologyID(), ont);
        URI physicalURI = physicalURIsByID.remove(oldID);
        physicalURIsByID.put(ont.getOntologyID(), physicalURI);
        resetImportsClosureCache();
    }


    private void resetImportsClosureCache() {
        importsClosureCache.clear();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Methods to save ontologies
    //
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////


    public void saveOntology(OWLOntology ontology) throws OWLOntologyStorageException, UnknownOWLOntologyException {
        OWLOntologyFormat format = getOntologyFormat(ontology);
        saveOntology(ontology, format);
    }


    public void saveOntology(OWLOntology ontology, OWLOntologyFormat ontologyFormat) throws OWLOntologyStorageException,
            UnknownOWLOntologyException {
        URI physicalURI = getPhysicalURIForOntology(ontology);
        saveOntology(ontology, ontologyFormat, physicalURI);
    }


    public void saveOntology(OWLOntology ontology, URI physicalURI) throws OWLOntologyStorageException,
            UnknownOWLOntologyException {
        OWLOntologyFormat format = getOntologyFormat(ontology);
        saveOntology(ontology, format, physicalURI);
    }


    public void saveOntology(OWLOntology ontology, OWLOntologyFormat ontologyFormat, URI physcialURI) throws
            OWLOntologyStorageException,
            UnknownOWLOntologyException {
        for (OWLOntologyStorer storer : ontologyStorers) {
            if (storer.canStoreOntology(ontologyFormat)) {
                storer.storeOntology(this, ontology, physcialURI, ontologyFormat);
                return;
            }
        }
        throw new OWLOntologyStorerNotFoundException(ontologyFormat);
    }


    public void saveOntology(OWLOntology ontology, OWLOntologyOutputTarget outputTarget) throws
            OWLOntologyStorageException,
            UnknownOWLOntologyException {
        saveOntology(ontology, getOntologyFormat(ontology), outputTarget);
    }


    public void saveOntology(OWLOntology ontology, OWLOntologyFormat ontologyFormat, OWLOntologyOutputTarget outputTarget) throws
            OWLOntologyStorageException,
            UnknownOWLOntologyException {
        for (OWLOntologyStorer storer : ontologyStorers) {
            if (storer.canStoreOntology(ontologyFormat)) {
                storer.storeOntology(this, ontology, outputTarget, ontologyFormat);
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Methods to add/remove ontology storers
    //
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////


    public void addOntologyStorer(OWLOntologyStorer storer) {
        ontologyStorers.add(0, storer);
    }


    public void removeOntologyStorer(OWLOntologyStorer storer) {
        ontologyStorers.remove(storer);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Methods to add/remove mappers etc.
    //
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////


    public void addURIMapper(OWLOntologyURIMapper mapper) {
        uriMappers.add(0, mapper);
    }


    public void clearURIMappers() {
        uriMappers.clear();
    }


    public void removeURIMapper(OWLOntologyURIMapper mapper) {
        uriMappers.remove(mapper);
    }


    public void addOntologyFactory(OWLOntologyFactory factory) {
        ontologyFactories.add(0, factory);
        factory.setOWLOntologyManager(this);
    }


    public void removeOntologyFactory(OWLOntologyFactory factory) {
        ontologyFactories.remove(factory);
    }


    /**
     * Uses the mapper mechanism to obtain a physical URI for an ontology
     * URI.
     *
     * @param ontologyURI The ontology URI for which the physical mapping is to be retrieved
     * @param quiet       If set to <code>true</code> and a mapping can't be found then a value of <code>null</code>
     *                    is returned.  If set to <code>false</code> and a mapping can't be found then an exception OWLOntologyURIMappingNotFoundException
     *                    is thrown.
     * @return The physical URI that corresponds to the ontology URI, or
     *         <code>null</code> if no physical URI can be found.
     */
    private URI getPhysicalURIFromOntologyURI(URI ontologyURI, boolean quiet) {
        for (OWLOntologyURIMapper mapper : uriMappers) {
            URI physicalURI = mapper.getPhysicalURI(ontologyURI);
            if (physicalURI != null) {
                return physicalURI;
            }
        }
        if (!quiet) {
            throw new OWLOntologyURIMappingNotFoundException(ontologyURI);
        } else {
            return null;
        }
    }


    private void installDefaultURIMappers() {
        // By defaut install the default mapper that simply maps
        // ontology URIs to themselves.
        addURIMapper(new OWLOntologyURIMapperImpl());
    }


    private void installDefaultOntologyFactories() {
        // The default factories are the ones that can load
        // ontologies from http:// and file:// URIs
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Listener stuff - methods to add/remove listeners
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////////

    private Map<OWLOntologyChangeListener, OWLOntologyChangeBroadcastStrategy> listenerMap = new LinkedHashMap<OWLOntologyChangeListener, OWLOntologyChangeBroadcastStrategy>();


    public void addOntologyChangeListener(OWLOntologyChangeListener listener) {
        listenerMap.put(listener, new DefaultChangeBroadcastStrategy());
    }


    /**
     * Broadcasts to attached listeners, using the various broadcasting
     * strategies that were specified for each listener.
     */
    private void broadcastChanges(List<? extends OWLOntologyChange> changes) {
        if (!broadcastChanges) {
            return;
        }
        for (OWLOntologyChangeListener listener : new ArrayList<OWLOntologyChangeListener>(listenerMap.keySet())) {
            OWLOntologyChangeBroadcastStrategy strategy = listenerMap.get(listener);
            if (strategy == null) {
                // This listener may have been removed during the broadcast of the changes,
                // so when we attempt to retrieve it from the map it isn't there (because
                // we iterate over a copy).
                continue;
            }
            try {
                // Handle exceptions on a per listener basis.  If we have
                // badly behaving listeners, we don't want one listener
                // to prevent the other listeners from receiving events.
                strategy.broadcastChanges(listener, changes);
            }
            catch (Exception e) {
                logger.warning("BADLY BEHAVING LISTENER: " + e);
                e.printStackTrace();
            }
        }
    }


    public void addOntologyChangeListener(OWLOntologyChangeListener listener,
                                          OWLOntologyChangeBroadcastStrategy strategy) {
        listenerMap.put(listener, strategy);
    }


    public void removeOntologyChangeListener(OWLOntologyChangeListener listener) {
        listenerMap.remove(listener);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //  Imports etc.
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void loadImports(OWLImportsDeclaration declaration) throws OWLOntologyCreationException {
        importsLoadCount++;
        try {
            loadOntology(declaration.getImportedOntologyURI());
        }
        catch (OWLOntologyCreationException e) {
            if (!silentMissingImportsHandling) {
                throw e;
            } else {
                // Silent
                MissingImportEvent evt = new MissingImportEvent(declaration.getImportedOntologyURI(), e);
                fireMissingImportEvent(evt);
            }
        }
        finally {
            importsLoadCount--;
        }
    }

    public void makeLoadImportRequest(OWLImportsDeclaration declaration) throws OWLOntologyCreationException {
        loadImports(declaration);
    }


    public void setSilentMissingImportsHandling(boolean b) {
        silentMissingImportsHandling = b;
    }


    public boolean isSilentMissingImportsHandling() {
        return silentMissingImportsHandling;
    }


    public void addMissingImportListener(MissingImportListener listener) {
        missingImportsListeners.add(listener);
    }


    public void removeMissingImportListener(MissingImportListener listener) {
        missingImportsListeners.remove(listener);
    }

    protected void fireMissingImportEvent(MissingImportEvent evt) {
        for (MissingImportListener listener : new ArrayList<MissingImportListener>(missingImportsListeners)) {
            listener.importMissing(evt);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //  Other listeners etc.
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////


    public void addOntologyLoaderListener(OWLOntologyLoaderListener listener) {
        loaderListeners.add(listener);
    }


    public void removeOntologyLoaderListener(OWLOntologyLoaderListener listener) {
        loaderListeners.remove(listener);
    }

    protected void fireStartedLoadingEvent(URI ontologyURI, URI physicalURI, boolean imported) {
        for (OWLOntologyLoaderListener listener : new ArrayList<OWLOntologyLoaderListener>(loaderListeners)) {
            listener.startedLoadingOntology(new OWLOntologyLoaderListener.LoadingStartedEvent(ontologyURI, physicalURI, imported));
        }
    }

    protected void fireFinishedLoadingEvent(URI ontologyURI, URI physicalURI, boolean imported, OWLOntologyCreationException ex) {
        for (OWLOntologyLoaderListener listener : new ArrayList<OWLOntologyLoaderListener>(loaderListeners)) {
            listener.finishedLoadingOntology(new OWLOntologyLoaderListener.LoadingFinishedEvent(ontologyURI, physicalURI, imported, ex));
        }
    }


    private class OntologyIRIVersionIRIPair {

        private URI ontologyIRI;

        private URI versionIRI;

        public OntologyIRIVersionIRIPair(URI ontologyIRI, URI versionIRI) {
            this.ontologyIRI = ontologyIRI;
            this.versionIRI = versionIRI;
        }

        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof OntologyIRIVersionIRIPair)) {
                return false;
            }
            OntologyIRIVersionIRIPair other = (OntologyIRIVersionIRIPair) obj;
            if (!ontologyIRI.equals(other.ontologyIRI)) {
                return false;
            }
            if (versionIRI != null) {
                if (other.versionIRI != null) {
                    return versionIRI.equals(other.versionIRI);
                } else {
                    return false;
                }
            }
            return false;
        }

        public int hashCode() {
            int hashCode = ontologyIRI.hashCode();
            if (versionIRI != null) {
                hashCode = hashCode + 37 * versionIRI.hashCode();
            }
            return hashCode;
        }
    }
}

