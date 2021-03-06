package org.nescent.VTO.lib;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.log4j.Logger;
import org.nescent.VTO.Builder;
import org.obo.datamodel.Dbxref;
import org.obo.datamodel.Link;
import org.obo.datamodel.OBOClass;
import org.obo.datamodel.OBOProperty;
import org.obo.datamodel.Synonym;

public class OBOMerger implements Merger {


	private OBOUtils sourceUtils = null;

	//String defaultPrefix;
	private final String idSuffix = ":%07d";

	private File sourceFile;
	private TaxonStore target;

	private boolean preserveID = false;
	private SynonymSource preserveSynonyms;
	private boolean updateObsoletes = false;
	private String subAction = Builder.SYNSUBACTION;  // default (currently only implemented) behavior is to merge synonyms

	static final Logger logger = Logger.getLogger(OBOMerger.class.getName());

	@Override
	public final boolean canAttach() {
		return true;
	}

	@Override
	public boolean canPreserveID(){
		return true;
	}

	@Override
	public void setPreserveID(boolean v){
		preserveID = v;
	}

	@Override
	public void setPreserveSynonyms(SynonymSource s){
		preserveSynonyms = s;
	}

	@Override
	public void setUpdateObsoletes(boolean v){
		updateObsoletes = v;
	}
	@Override
	public void setSource(File source){
		sourceFile = source;
	}

	@Override
	public void setTarget(TaxonStore targetStore){
		target = targetStore;
	}

	/**
	 * @param sa specifies whether this merges synonyms or cross references
	 */
	@Override
	public void setSubAction(String sa){
		if (Builder.XREFSUBACTION.equals(sa)){
			throw new IllegalArgumentException("Xref merging not currently supported by OBOMerger");
		}
		subAction = sa;
	}

	@Override
	public void setURITemplate(String template) {
		// TODO Auto-generated method stub

	}



	@Override
	public void merge(String prefix) {
		logger.info("Loading OBO file " + sourceFile);
		sourceUtils = new OBOUtils(sourceFile.getAbsolutePath());
		logger.info("Finished loading");
		int termCount = 0;
		int synCount = 0;
		target.updateIDGenerator(prefix);
		Collection <OBOClass> allTerms = sourceUtils.getTerms();
		for (OBOClass term : allTerms){
			Term matchingTerm = target.getTermbyName(term.getName());
			if (matchingTerm != null){
				termCount++;
				//SynonymI s = target.makeSynonymWithXref(term.getName(), prefix, term.getID());
				//matchingTerm.addSynonym(s);
				// add synonyms from term
				for (Synonym syn : term.getSynonyms()){
					if (sourceUtils.isKnownSynonymType(syn.getSynonymType())) {
						final String stypeName = syn.getSynonymType().getName();
						if (!syn.getXrefs().isEmpty()){  //assumes one xref per synonym (probably safe, but noted)
							final Dbxref ref = syn.getXrefs().iterator().next();
							SynonymI newSyn = target.makeTypedSynonymWithXref(syn.getText(), stypeName, ref.getDatabase(), ref.getDatabaseID());
							matchingTerm.addSynonym(newSyn);
							synCount++;
						}
						else{ //no xrefs
							SynonymI newSyn = target.makeTypedSynonym(syn.getText(), stypeName);
							matchingTerm.addSynonym(newSyn);
							synCount++;
						}
					}
					else {  //not a common name
						if (!syn.getXrefs().isEmpty()){  //assumes one xref per synonym (probably safe, but noted)
							final Dbxref ref = syn.getXrefs().iterator().next();
							SynonymI newSyn = target.makeSynonymWithXref(syn.getText(), ref.getDatabase(), ref.getDatabaseID());
							matchingTerm.addSynonym(newSyn);
							synCount++;
						}
						else{ //no xrefs
							SynonymI newSyn = target.makeSynonym(syn.getText());
							matchingTerm.addSynonym(newSyn);
							synCount++;
						}
					}
				}
			}
			if (termCount % 1000 == 0){
				logger.info("Processed " + termCount + " terms; added " + synCount + " synonyms");
			}
		}
	}

	/**
	 * @param targetParentName name of parent node for attached clade
	 * @param sourceRootName name of root node (child of parent) for attached clade
	 * @param prefix default prefix for target ontology
	 */
	@Override
	public void attach(String targetParentName, String sourceRootName, String prefix) {
		logger.info("Loading OBO file " + sourceFile);
		sourceUtils = new OBOUtils(sourceFile.getAbsolutePath());
		logger.info("Finished loading");
		logger.info("Attach started target size = " + target.getTerms().size());
		logger.info("               source size = " + sourceUtils.getTerms().size());
		target.updateIDGenerator(prefix);
		if (targetParentName == null || targetParentName.equalsIgnoreCase(sourceRootName))
			copyRootToTarget(sourceRootName,prefix);
		else{
			OBOClass sourceRoot = sourceUtils.lookupTermByName(sourceRootName);  //this is the root of the clade - copy this and its children
			if (sourceRoot == null){
				throw new RuntimeException("Can not attach unknown root: " + sourceRootName);
			}
			Term targetParent = target.getTermbyName(targetParentName);
			if (targetParent == null){
				throw new RuntimeException("Can not attach to unknown parent: " + targetParentName);
			}
			Term targetRoot = copyTerm(sourceRoot,prefix);
			target.attachParent(targetRoot, targetParent);
			addChildren(sourceRoot,targetRoot,target,prefix);
		}
		if (updateObsoletes){
			target.processObsoletes();
		}

	}

	private void copyRootToTarget(String sourceRootName, String prefix){
		OBOClass sourceRoot = sourceUtils.lookupTermByName(sourceRootName);  //this is the root of the clade - copy this and its children
		if (sourceRoot == null){
			logger.error("Can not attach " + sourceFile.getAbsolutePath() + " specified root: " + sourceRootName + " is unknown to " + sourceUtils);
			return;
		}
		if (target.getTermbyName(sourceRootName) != null){
			logger.error("Can not attach " + sourceFile.getAbsolutePath() + " specified root: " + sourceRootName + " already exists in " + target);
		}
		Term targetRoot;
		// this should be copyTerm
		if (preserveID){
			targetRoot = target.addTermbyID(sourceRoot.getID(),sourceRootName);
		}
		else {
			targetRoot = target.addTerm(sourceRootName,prefix);
		}			
		logger.info("Assigning " + sourceRootName + " as root");
		if (sourceUtils.getRankString(sourceRoot) != null)
			target.setRankFromName(targetRoot, sourceUtils.getRankString(sourceRoot));
		addChildren(sourceRoot,targetRoot,target,prefix);
	}



	// Note: parentClass is from the obo tree being attached, parentTerm is the copy in the target tree
	// so parentTerm.asOBOClass != parentClass
	private void addChildren(OBOClass sourceParent, Term targetParent, TaxonStore target, String prefix){
		final Collection<Link> childLinks = sourceParent.getChildren();
		for(Link l : childLinks){
			OBOProperty lType = l.getType();
			if (OBOUtils.ISA_PROPERTY.equals(lType.getID())){
				OBOClass childClass = (OBOClass)l.getChild();
				Term childTerm = copyTerm(childClass,prefix);
				
				target.attachParent(childTerm, targetParent);
				addChildren(childClass,childTerm,target,prefix);			
			}
		}
	}

	private Term copyTerm(OBOClass sourceClass, String prefix){
		Term targetTerm = null;
		if (preserveID){
			targetTerm = target.addTermbyID(sourceClass.getID(),sourceClass.getName());
		}
		else{
			final String[] idFields = sourceClass.getID().split(":");
			if (idFields.length == 2){
				targetTerm = target.addTerm(sourceClass.getName(),prefix);				
				target.addXRefToTerm(targetTerm,idFields[0],idFields[1]);  // could be an alternate ID, no, not best practice according to Allan
			}
			else{
				logger.warn("Could not split OBOID " + sourceClass.getID() + " to generate xref in target term");
				targetTerm = target.addTerm(sourceClass.getName(),prefix);				
			}
		}
		if (sourceClass.getDbxrefs() != null){
			for (Dbxref d : sourceClass.getDbxrefs()){
				target.addXRefToTerm(targetTerm, d.getDatabase(), d.getDatabaseID());
			}
		}
		for (Synonym syn : sourceClass.getSynonyms()){
			if (sourceUtils.isKnownSynonymType(syn.getSynonymType())) {
				final String stypeName = syn.getSynonymType().getName();
				if (!syn.getXrefs().isEmpty()){  //assumes one xref per synonym (probably safe, but noted)
					final Dbxref ref = syn.getXrefs().iterator().next();
					SynonymI newSyn = target.makeTypedSynonymWithXref(syn.getText(), stypeName, ref.getDatabase(), ref.getDatabaseID());
					targetTerm.addSynonym(newSyn);
				}
				else{ //no xrefs
					SynonymI newSyn = target.makeTypedSynonym(syn.getText(),stypeName);
					targetTerm.addSynonym(newSyn);
				}
			}
			else {  //not a common name
				if (!syn.getXrefs().isEmpty()){  //assumes one xref per synonym (probably safe, but noted)
					final Dbxref ref = syn.getXrefs().iterator().next();
					SynonymI newSyn = target.makeSynonymWithXref(syn.getText(), ref.getDatabase(), ref.getDatabaseID());
					targetTerm.addSynonym(newSyn);
				}
				else{ //no xrefs
					SynonymI newSyn = target.makeSynonym(syn.getText());
					targetTerm.addSynonym(newSyn);
				}
			}
		}
		if (sourceUtils.getRankString(sourceClass) != null)
			target.setRankFromName(targetTerm, sourceUtils.getRankString(sourceClass));
		if (sourceUtils.isExtinct(sourceClass))
			sourceUtils.setExtinct(targetTerm.asOBOClass());
		return targetTerm;
	}



}
