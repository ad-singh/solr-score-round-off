package solr;

import org.apache.lucene.document.Document;



public class WrapperClass {

	int docId;
	private float score;
	private Document document;
	public float getScore() {
		return score;
	}
	public void setScore(float score) {
		this.score = score;
	}
	public Document getDocument() {
		return document;
	}
	public void setDocument(Document document) {
		this.document = document;
	}

	public String get(String columnName){
		if(columnName.equalsIgnoreCase("score"))
			return String.valueOf(score);
		else
			return document.get(columnName);
	}
	public WrapperClass(float score, Document document,int docId) {
		this.score = score;
		this.document = document;
		this.docId=docId;
	}
	public int getDocId() {
		return docId;
	}
	public void setDocId(int docId) {
		this.docId = docId;
	}
	
}

