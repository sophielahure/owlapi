package org.semanticweb.owlapi6.atomicdecomposition;

import java.util.Collection;

import org.semanticweb.owlapi6.model.OWLAxiom;
import org.semanticweb.owlapi6.model.OWLAxiomVisitor;
import org.semanticweb.owlapi6.model.OWLObject;

/**
 * @author ignazio Locality checker
 */
public interface LocalityChecker extends OWLAxiomVisitor {

    /**
     * @param axiom axiom to check
     * @return true if axiom is local
     */
    boolean local(OWLAxiom axiom);

    /**
     * allow the checker to preprocess an ontology if necessary
     *
     * @param vec collection of axioms
     */
    void preprocessOntology(Collection<AxiomWrapper> vec);

    /**
     * @param sig signature to use
     */
    void setSignatureValue(Signature sig);

    /**
     * @return signature being used
     */
    Signature getSignature();

    /**
     * @param expr expression to evaluate
     * @return true if expression is top equivalent
     */
    boolean isTopEquivalent(OWLObject expr);

    /**
     * @param expr expression to evaluate
     * @return true if expression is bottom equivalent
     */
    boolean isBotEquivalent(OWLObject expr);
}