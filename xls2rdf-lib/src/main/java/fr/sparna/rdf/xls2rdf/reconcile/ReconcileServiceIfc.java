package fr.sparna.rdf.xls2rdf.reconcile;

import java.util.Map;

public interface ReconcileServiceIfc {

	
	public Map<String, ReconcileResultIfc> reconcile(Map<String, ReconcileQueryIfc> queries);
	
}
