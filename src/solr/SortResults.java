package solr;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocList;
import org.apache.solr.search.DocSlice;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SolrIndexSearcher.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SortResults {

	private static final Logger logger = LoggerFactory.getLogger(SortResults.class);
	  public void sortSolrResults(QueryResult result,ResponseBuilder rb,SolrIndexSearcher searcher) throws IOException {
		  long startTime=System.currentTimeMillis();
		  SolrQueryRequest req=rb.req;
		  int precisionStep=req.getParams().getInt("scorePrecision",4);
		  int precision=(int)Math.pow(10, precisionStep);
		  String sortOrder=req.getParams().get("sort");
		  if(ScorePrecisionComponent.isBlank(sortOrder))
		  {
			  logger.debug("sort order is empty so not sorting");
			  return;
		  }
		  String compareColumn=null;
		  String secondryColumn=null;
		  boolean primaryOrder=false;
		  String[]conditions=sortOrder.split(",");
		  if(conditions.length>1){
			  secondryColumn=sortOrder.substring(sortOrder.indexOf(",")+1);
		  }
		  String []sarr=conditions[0].split(" ");
		  if(sarr.length==2)
		  {
			  compareColumn=sarr[0];
			  if(sarr[1].equalsIgnoreCase("desc"))
				  primaryOrder=true;
		  }else{
			  logger.error("Invalid sort condition");
			  return;
		  }
		  DocList docList = result.getDocList();
		  int nDocs = docList.size();
		  int[] docIds=new int[nDocs];
		  float[] scores = new float[nDocs]; // doc scores, parallel to sortedIds
		  DocList docs = result.getDocList();
		  DocIterator it = docs.iterator();
		  LinkedList<WrapperClass> wrapperList=new LinkedList<WrapperClass>();
		  while(it.hasNext()){
			  int docId=it.nextDoc();
			  Document doc= searcher.doc(docId);
			  Float orignalScore=docs.hasScores() ? it.score() : Float.NaN;
			  Float score=Float.NaN;
			  if(orignalScore!=Float.NaN){
				  orignalScore*=precision;
				  score=(float)orignalScore.intValue()/precision;
			  }
			  wrapperList.add(new WrapperClass(score, doc, docId));

		  }
		  if(wrapperList!= null &&!wrapperList.isEmpty()){

			  sort(wrapperList, compareColumn, secondryColumn, primaryOrder);
			  for(int i=0;i<wrapperList.size();i++){
				  docIds[i]=wrapperList.get(i).getDocId();
				  scores[i]=wrapperList.get(i).getScore();
			  }
			  DocList list=new DocSlice(0, wrapperList.size(), docIds, scores, docs.matches(), docs.maxScore());
			  result.setDocList(list);

		  }

	logger.debug("Total time spend in sorting results is: "+(System.currentTimeMillis()-startTime)+"ms");
	  }
	  
	  private void sort(List<WrapperClass> matchedList,final String compareColumn,final String secondryColumn,final boolean primaryOrder){
		  
		  
		  class SortByDC implements Comparator<WrapperClass> {
				
			  String secCol;
			  boolean secOrder;
			  String tertiary;
			  
			  
			  SortByDC(String secCol,boolean secOrder,String tertiary ){
				  this.secCol=secCol;
				  this.secOrder=secOrder;
				  this.tertiary=tertiary;
				  
			  }
				@Override
				public int compare(WrapperClass o1, WrapperClass o2) {
					
					try{
						Float f1=Float.parseFloat(o1.get(compareColumn));
						Float f2=Float.parseFloat(o2.get(compareColumn));
						
						if(!ScorePrecisionComponent.isBlank(secondryColumn)&& f1.equals(f2)){
							
							return  customCompare(o1,o2,secCol,secOrder,tertiary);
						}
						if(primaryOrder)
						  return f2.compareTo(f1);
						else
							return f1.compareTo(f2);
					}catch(Exception e){
						e.printStackTrace();
					}
					String s1=o1.get(compareColumn);
					String s2=o2.get(compareColumn);
					if(!ScorePrecisionComponent.isBlank(secondryColumn) && s1.equalsIgnoreCase(s2))
					{
						return customCompare(o1,o2,secCol,secOrder,tertiary);
					}
					if(primaryOrder)
						return s2.compareTo(s1);
					else
						return s1.compareTo(s2);
				}

				
				
				
			}
		  String secCol="";
		  boolean secOrder=false;
		  String tertiary="";
		  if(!ScorePrecisionComponent.isBlank(secondryColumn)){
			  String[]conditions=secondryColumn.split(",");
			  if (conditions.length>1) {
				  tertiary=secondryColumn.substring(secondryColumn.indexOf(",")+1);
			}
			  String[] sarr=conditions[0].trim().split(" ");
			  if(sarr.length==2){
				  secCol=sarr[0];
				  if(sarr[1].equalsIgnoreCase("DESC"))
					  secOrder=true;
			  }
		  }
				Collections.sort(matchedList, new SortByDC(secCol,secOrder,tertiary));
		
	}
	  
	  private int customCompare(WrapperClass o1, WrapperClass o2,String column, boolean order,String secondryConditions) {
			
			String secCol="";
			boolean secOrder=false;
			String otherCond="";
			if(!ScorePrecisionComponent.isBlank(secondryConditions)){
				  String[]conditions=secondryConditions.split(",");
				  if (conditions.length>1) {
					  otherCond=secondryConditions.substring(0, secondryConditions.indexOf(","));
				}
				  String[] sarr=conditions[0].split(" ");
				  if(sarr.length==2){
					  secCol=sarr[0];
					  if(sarr[1].equalsIgnoreCase("DESC"))
						  secOrder=true;
				  }
				  try{
					  Float f1=Float.parseFloat(o1.get(column));
					  Float f2=Float.parseFloat(o2.get(column));
					  if(f1.equals(f2))
					  {
						  return customCompare(o1, o2, secCol, secOrder, otherCond);
					  }
					  if(order)
							return f2.compareTo(f1);
					  else
							return f1.compareTo(f2);
				  }catch(Exception e){
					  e.printStackTrace();
				  }
				  String s1=o1.get(column);
				  String s2=o2.get(column);
				  if(s1.equalsIgnoreCase(s2))
					  return customCompare(o1, o2, secCol, secOrder, otherCond);
				  else
				  {
					  if(order)
							return s2.compareTo(s1);
					  else
							return s1.compareTo(s2);
				  }
				  
				  
			  }else{
					try{
						Float f1=Float.parseFloat(o1.get(column));
						Float f2=Float.parseFloat(o2.get(column));
						if(order)
							return f2.compareTo(f1);
						else
							return f1.compareTo(f2);
					}catch(Exception e){
						
					}
					if(order)
						return o2.get(column).compareTo(o1.get(column));
					else
						return o1.get(column).compareTo(o2.get(column));

			  }
			
			
		}



}
