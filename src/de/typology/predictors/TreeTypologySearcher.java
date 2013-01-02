package de.typology.predictors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;

import de.typology.trainers.SuggestTree;
import de.typology.trainers.SuggestTree.Node;
import de.typology.trainers.SuggestTree.Pair;
import de.typology.trainers.TreeTypologyIndexer;
import de.typology.utils.Config;

public class TreeTypologySearcher extends TreeSearcher{
	public TreeTypologySearcher(int n, int k, int joinLength,HashMap <Integer,HashMap<String,SuggestTree<Float>>> treeMapMap) {
		super(n, k, joinLength);
		TreeTypologySearcher.treeMapMap=treeMapMap;

	}

	protected static HashMap <Integer,HashMap<String,SuggestTree<Float>>> treeMapMap;
	protected int joinLength;
	protected int k;
	protected int n;
	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {
		TreeTypologyIndexer tti=new TreeTypologyIndexer();
		HashMap <Integer,HashMap<String,SuggestTree<Float>>> treeMapMap= tti.run(Config.get().normalizedEdges);
		TreeTypologySearcher tts=new TreeTypologySearcher(5, 5, 12,treeMapMap);
		//tts.query("1 12 123 1991", "a", "1991 an");
		HashMap<String, Float>result=tts.search("1991 1991 1991 1991", "a","");
		for(Entry<String, Float> e:result.entrySet()){
			System.out.println(e.getKey()+" "+e.getValue());
		}

	}



	@Override
	public HashMap<String, Float> search(String q, String prefix, String match) {
		HashMap<String, Float> result = new HashMap<String, Float>();
		ArrayList<SuggestTree<Float>> trees;
		String[] terms = q.split(" ");

		int edge = 1;
		for (int i = terms.length - 1; i >= Math.max(0, terms.length - 4); i--) {
			trees=new ArrayList<SuggestTree<Float>>();
			//get list of trees
			String t1=terms[i].substring(0, 1);
			if(prefix.length()!=0){
				if(treeMapMap.get(edge).containsKey(t1)){
					trees.add(treeMapMap.get(edge).get(t1));			
				}else{
					String p1=prefix.substring(0, 1);
					if(treeMapMap.get(edge).containsKey(t1+p1)){
						trees.add(treeMapMap.get(edge).get(t1+p1));
					}else{
						if(treeMapMap.get(edge).containsKey(t1+"other")){
							trees.add(treeMapMap.get(edge).get(t1+"other"));
						}else{trees.add(treeMapMap.get(edge).get("other"));}
					}
				}
			}else{
				for(Entry<String, SuggestTree<Float>> entry:treeMapMap.get(edge).entrySet()){
					if(entry.getKey().startsWith(t1)){
						trees.add(entry.getValue());
					}
				}
			}

			float weight=1;
			for(SuggestTree<Float> tree:trees){
				Node<Float> node=tree.getBestSuggestions(terms[i]+" "+prefix);
				if(node!=null){
					for(int join=0;join<this.joinLength&&join<node.listLength();join++){
						Pair<Float> pair=node.getSuggestion(join);
						String key=pair.getString();
						Float value=pair.getScore();
						if (result.containsKey(key)) {
							result.put(key, weight * value + result.get(key));
						} else {
							result.put(key, weight * value);
						}
					}
				}
			}
			edge++;
		}

		return result;
	}


	@Override
	public String getFileName() {
		String name = "";
		if (Config.get().useWeights) {
			name = name.concat("weighted-");
		}
		name = name.concat("typo-" + this.n+ "-joinLengh-" + this.joinLength
				+ "-" + Config.get().sampleRate + Config.get().splitDataRatio
				+ ".log");
		// TODO Auto-generated method stub
		return name;
	}




}
