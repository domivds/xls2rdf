package fr.sparna.rdf.xls2rdf.reconcile;

import static fr.sparna.rdf.xls2rdf.ExcelHelper.getCellValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fr.sparna.rdf.xls2rdf.Xls2RdfException;
import fr.sparna.rdf.xls2rdf.Xls2RdfMessageListenerIfc;
import fr.sparna.rdf.xls2rdf.Xls2RdfMessageListenerIfc.MessageCode;

public class PreloadedReconciliableValueSet implements ReconciliableValueSetIfc {

	private static Logger log = LoggerFactory.getLogger(PreloadedReconciliableValueSet.class.getName());
	
	private static final int BATCH_SIZE = 20;
	
	private transient ReconcileServiceIfc reconcileService;
	private boolean failOnNoMatch = true;
	
	private Map<String, IRI> reconciledValues;
	
	
	public PreloadedReconciliableValueSet(
			ReconcileServiceIfc reconcileService,
			boolean failOnNoMatch
	) {
		this.reconcileService = reconcileService;
		this.failOnNoMatch = failOnNoMatch;
		this.reconciledValues = new HashMap<String, IRI>();	
	}
	
	/* (non-Javadoc)
	 * @see fr.sparna.rdf.skos.xls2skos.reconcile.ReconciliableValueSetIfc#getReconciledValue(java.lang.String)
	 */
	@Override
	public IRI getReconciledValue(String value) {
		return this.reconciledValues.get(value);
	}
	
	public static Map<String, List<String>> extractDistinctValues(Sheet sheet, int columnIndex, int headerRowIndex) {
		log.debug("Extracting distinct values from column index "+CellReference.convertNumToColString(columnIndex)+".");
		
		Map<String, List<String>> result = new HashMap<String, List<String>>();
		for (int rowIndex = (headerRowIndex + 1); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
			Row row = sheet.getRow(rowIndex);
			if(row != null) {
				Cell cell = row.getCell(columnIndex);
				
				if(cell != null) {
					String value = getCellValue(cell);			
					if(!result.containsKey(value)) {
						List<String> cells = new ArrayList<String>();
						cells.add(new CellReference(cell).formatAsString());
						result.put(value, cells);
					} else {
						result.get(value).add(new CellReference(cell).formatAsString());
					}
				}
			}
		}
		
		log.debug("Extracted "+result.size()+" distinct values from column "+CellReference.convertNumToColString(columnIndex));
		return result;
	}
	
	public void initReconciledValues(Map<String, List<String>> valuesWithCells, IRI reconcileType, Xls2RdfMessageListenerIfc messageListener) {
		log.debug("Reconciling "+valuesWithCells.size()+" values against type "+ reconcileType +" ...");
		
		// extract list of values
		List<String> values = new ArrayList<String>(valuesWithCells.keySet());
		
		// iterate
		int currentOffset = 0;
		while(currentOffset < values.size()) {
			int finalOffset = java.lang.Math.min(currentOffset + BATCH_SIZE, values.size());
			log.debug("Processing batch from "+currentOffset+" to "+finalOffset+"...");
			List<String> batch = values.subList(currentOffset, finalOffset);
			this.reconciledValues.putAll(reconcileBatch(batch, reconcileType, messageListener, valuesWithCells));
			currentOffset += BATCH_SIZE;
		}
	}
	
	private Map<String, IRI> reconcileBatch(List<String> values, IRI reconcileType, Xls2RdfMessageListenerIfc messageListener, Map<String, List<String>> cellReferences) {
		
		// build the queries Map
		Map<String, ReconcileQueryIfc> queries = new HashMap<String, ReconcileQueryIfc>();
		for (String aValue : values) {
			queries.put("q"+values.indexOf(aValue), new SimpleReconcileQuery(
					aValue,
					(reconcileType != null)?Collections.singletonList(reconcileType.toString()):null
			));
		}
		
		// call ReconcileService
		Map<String, ReconcileResultIfc> reconcileResults = this.reconcileService.reconcile(queries);
		
		// parse results
		Map<String, IRI> result = new HashMap<String, IRI>();
		for (Map.Entry<String, ReconcileResultIfc> anEntry : reconcileResults.entrySet()) {
			
			String initialValue = queries.get(anEntry.getKey()).getQuery();
			
			if(anEntry.getValue().getMatches() == null || anEntry.getValue().getMatches().size() == 0) {
				// no reconciliation result for this value
				String message = "Unable to reconcile value '"+ initialValue +"' on type/scheme <"+ reconcileType +">";
				log.error(message);
				if(this.failOnNoMatch) {					
					throw new Xls2RdfException(message);
				} else {
					messageListener.onMessage(MessageCode.UNABLE_TO_RECONCILE_VALUE, cellReferences.get(initialValue).stream().collect(Collectors.joining(", ")), message);
				}
			} else {
				// pick the first one, assuming only one result for now
				String matchResult = anEntry.getValue().getMatches().get(0).getId();
				log.debug("Value '"+initialValue+"' reconciled to <"+matchResult+">");
				result.put(initialValue, SimpleValueFactory.getInstance().createIRI(anEntry.getValue().getMatches().get(0).getId()));
			}
		}
		
		return result;
	}
	
	
}
