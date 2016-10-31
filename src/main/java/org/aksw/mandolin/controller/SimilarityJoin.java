package org.aksw.mandolin.controller;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.TreeSet;

import jp.ndca.similarity.join.PPJoin;
import jp.ndca.similarity.join.StringItem;
import jp.ndca.similarity.join.Tokenizer;

import org.aksw.mandolin.controller.NameMapper.Type;
import org.aksw.mandolin.model.Cache;
import org.aksw.mandolin.model.ComparableLiteral;
import org.aksw.mandolin.reasoner.PelletReasoner;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.NodeFactory;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.vocabulary.OWL;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;

/**
 * @author Tommaso Soru <tsoru@informatik.uni-leipzig.de>
 *
 */
public class SimilarityJoin {
	
	private final static Logger logger = LogManager.getLogger(SimilarityJoin.class);
	
	public static final String SIMILAR_PREFIX = "http://mandolin.aksw.org/ontology#similar";
	
	public static final String SIMILAR_TO_PREFIX = "http://mandolin.aksw.org/ontology#similarTo";
	
	static HashMap<String, String> hashes = new HashMap<>();
	
	public static final String similarCompositePropertyURI(int thr, String uri) {
		
		String s;
		
		if(hashes.containsKey(uri))
			s = hashes.get(uri);
		else {
			s = DigestUtils.sha1Hex(uri);
			hashes.put(uri, s);
		}
		
		return SIMILAR_PREFIX + thr + "-" + s;
	}
	
	public static final String similarToURI(int thr) {
		// no such property
		if(thr <= 0 || thr >= 100)
			return null;
		return SIMILAR_TO_PREFIX + thr;
	}

	
	public static void build(NameMapper map, TreeSet<ComparableLiteral> setOfStrings,
			Cache cache, final String BASE, final int THR_MIN, final int THR_MAX, final int THR_STEP) {
		
		PPJoin ppjoin = new PPJoin();
		Tokenizer tok = ppjoin.getTokenizer();
		HashMap<Integer, ComparableLiteral> dataset = new HashMap<>();

		Iterator<ComparableLiteral> it = setOfStrings.iterator();
		for (int i = 0; it.hasNext(); i++) {
			ComparableLiteral lit = it.next();
			String val = lit.getVal();
			cache.stringItems.add(new StringItem(tok.tokenize(val, false), i));
			dataset.put(i, lit);
		}

		logger.trace(cache.stringItems.size());
		List<StringItem> stringItems = cache.stringItems;

		StringItem[] strDatum = stringItems.toArray(new StringItem[stringItems
				.size()]);
		Arrays.sort(strDatum);

		ppjoin.setUseSortAtExtractPairs(false);
		
		// open NT file of similarity joins.
		final FileOutputStream output;
		try {
			output = new FileOutputStream(new File(BASE + "/model-sim.nt"));
		} catch (FileNotFoundException e) {
			logger.fatal(e.getMessage());
			throw new RuntimeException("Cannot open file "+BASE+"/model-sim.nt of similarity joins.");
		}
		
		final StreamRDF writer = StreamRDFWriter.getWriterStream(output, Lang.NT);
		writer.start();
		
		int cTBox = 0, cABox = 0;
		
		for (int thr = THR_MIN; thr <= THR_MAX; thr += THR_STEP) {
			
			String rel = similarToURI(thr);
			if(rel == null)
				continue;
			if(rel.isEmpty())
				continue;
			Node relNode = NodeFactory.createURI(rel);
			
			writer.triple(new Triple(relNode, RDF.type.asNode(), OWL.SymmetricProperty.asNode()));
			writer.triple(new Triple(relNode, RDF.type.asNode(), OWL.TransitiveProperty.asNode()));
			cTBox += 2;
			
			for(int thrj = THR_MIN; thrj < thr; thrj += THR_STEP) {
				Triple t = new Triple(relNode, RDFS.subPropertyOf.asNode(), NodeFactory.createURI(similarToURI(thrj)));
				logger.trace(t);
				writer.triple(t);
				cTBox++;
			}
			
//			System.out.println("thr = " + (thr / 100.0));
			List<Entry<StringItem, StringItem>> result = ppjoin.extractPairs(
					strDatum, thr / 100.0);
			for (Entry<StringItem, StringItem> entry : result) {
				ComparableLiteral lit1 = dataset.get(entry.getKey().getId());
				ComparableLiteral lit2 = dataset.get(entry.getValue().getId());
				String relName = map.add(rel, Type.RELATION);
				map.addRelationship(relName, 
						map.getName(lit1.getUri()), map.getName(lit2.getUri()));
				
				// add similarTo relationship
				writer.triple(new Triple(NodeFactory.createURI(lit1.getUri()),
						relNode,
						NodeFactory.createURI(lit2.getUri())));
				
				int c = compositeRelations(writer, map, thr,
						lit1.getUri(), lit2.getUri());
				cABox += c;
				
				logger.trace(lit1.getUri() + " <=> " + lit2.getUri());
				logger.trace(lit1.getVal() + " <=> " + lit2.getVal());
			}
			
			cABox += result.size();
			
		}
		
		// close NT file
		writer.finish();
		
		logger.info("Triples added after similarity join: TBox="+cTBox+", ABox="+cABox);
		
		// computing closure on similarity joins
//		PelletReasoner.closure(BASE + "/model-sim.nt", BASE + "/model-sim-fwc.nt");
		try {
			FileUtils.copyFile(new File(BASE + "/model-sim.nt"), new File(BASE + "/model-sim-fwc.nt"));
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}


	private static int compositeRelations(StreamRDF writer, NameMapper map, int thr,
			String wURI, String zURI) {
		
		String w = map.getName(wURI), z = map.getName(zURI);
		Node wNode = NodeFactory.createURI(wURI);
		Node zNode = NodeFactory.createURI(zURI);
		
		TreeSet<String> rships = map.getRelationships();
		TreeSet<String> wTree = new TreeSet<>();
		TreeSet<String> zTree = new TreeSet<>();
		for(String rship : rships) {
			String[] rsh = rship.split("#");
			// w and z can be only in 2nd position, as they are datatypes
			if(rsh[2].equals(w))
				wTree.add(rship);
			if(rsh[2].equals(z))
				zTree.add(rship);
		}
		
		logger.trace("wTree = " + wTree);
		logger.trace("zTree = " + zTree);
		
		// forall x : (x, rel, w) . add (x, extRel, z)
		for(String rship : wTree) {
			String[] rsh = rship.split("#");
			String rel = rsh[0], subj = rsh[1];
			
			String extRelURI = similarCompositePropertyURI(thr, rel);
			Node extRelNode = NodeFactory.createURI(extRelURI);
			String extRelName = map.add(extRelURI, Type.RELATION);
			logger.trace(rel + " => "+extRelURI + " => "+extRelName);
			
			map.addRelationship(extRelName, subj, z);
			logger.trace(extRelName + "#"+ subj +"#"+ z);
			
			// add composite-relation triple
			Triple t = new Triple(NodeFactory.createURI(map.getURI(subj)),
					extRelNode,
					zNode);
			logger.trace(t);
			writer.triple(t);
			
		}
		
		// forall y : (y, rel, z) . add (y, extRel, w)
		for(String rship : zTree) {
			String[] rsh = rship.split("#");
			String rel = rsh[0], subj = rsh[1];
			
			String extRelURI = similarCompositePropertyURI(thr, rel);
			Node extRelNode = NodeFactory.createURI(extRelURI);
			String extRelName = map.add(extRelURI, Type.RELATION);
			logger.trace(rel + " => "+extRelURI + " => "+extRelName);
			
			map.addRelationship(extRelName, subj, w);
			logger.trace(extRelName + "#"+ subj +"#"+ w);
			
			// add composite-relation triple
			Triple t = new Triple(NodeFactory.createURI(map.getURI(subj)),
					extRelNode,
					wNode);
			logger.trace(t);
			writer.triple(t);
			
		}
		
		return wTree.size() + zTree.size();
		
	}

}
