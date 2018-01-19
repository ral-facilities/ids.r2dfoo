package org.icatproject.ids;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonValue;

import org.icatproject.Datafile;
import org.icatproject.Dataset;
import org.icatproject.ICAT;
import org.icatproject.IcatExceptionType;
import org.icatproject.IcatException_Exception;
import org.icatproject.icat.client.IcatException;
import org.icatproject.icat.client.Session;
import org.icatproject.ids.exceptions.BadRequestException;
import org.icatproject.ids.exceptions.InsufficientPrivilegesException;
import org.icatproject.ids.exceptions.InternalException;
import org.icatproject.ids.exceptions.NotFoundException;
import org.icatproject.ids.plugin.DsInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataSelection {

	private static final org.icatproject.icat.client.ICAT restIcat = PropertyHandler.getInstance().getRestIcat();
	private final static Logger logger = LoggerFactory.getLogger(DataSelection.class);

	/**
	 * Checks to see if the investigation, dataset or datafile id list is a
	 * valid comma separated list of longs. No spaces or leading 0's. Also
	 * accepts null.
	 */
	public static List<Long> getValidIds(String thing, String idList) throws BadRequestException {

		List<Long> result;
		if (idList == null) {
			result = Collections.emptyList();
		} else {
			String[] ids = idList.split("\\s*,\\s*");
			result = new ArrayList<>(ids.length);
			for (String id : ids) {
				try {
					result.add(Long.parseLong(id));
				} catch (NumberFormatException e) {
					throw new BadRequestException("The " + thing + " parameter '" + idList + "' is not a valid "
							+ "string representation of a comma separated list of longs");
				}
			}
		}
		return result;
	}

	private Map<Long, DsInfo> dsInfos;
	private ICAT icat;
	private String sessionId;
	private boolean dfWanted;
	private List<Long> dfids;
	private List<Long> dsids;
	private List<Long> invids;
	private Set<DfInfoImpl> dfInfos;
	private Set<Long> emptyDatasets;
	private boolean dsWanted;
	private Session restSession;

	public static int maxEntities = PropertyHandler.getInstance().getMaxEntities();

	public enum Returns {
		DATASETS, DATASETS_AND_DATAFILES, DATAFILES
	}

	public DataSelection(ICAT icat, String sessionId, String investigationIds, String datasetIds, String datafileIds,
			Returns returns)
			throws BadRequestException, NotFoundException, InsufficientPrivilegesException, InternalException {

		this.icat = icat;
		this.sessionId = sessionId;
		dfids = getValidIds("datafileIds", datafileIds);
		dsids = getValidIds("datasetIds", datasetIds);
		invids = getValidIds("investigationIds", investigationIds);
		dfWanted = returns == Returns.DATASETS_AND_DATAFILES || returns == Returns.DATAFILES;
		dsWanted = returns == Returns.DATASETS_AND_DATAFILES || returns == Returns.DATASETS;
		restSession = restIcat.getSession(sessionId);
		logger.debug("dfids: {} dsids: {} invids: {}", dfids, dsids, invids);
		resolveDatasetIds();

	}

	public Map<Long, DsInfo> getDsInfo() {
		return dsInfos;
	}

	private void resolveDatasetIds()
			throws NotFoundException, InsufficientPrivilegesException, InternalException, BadRequestException {
		dsInfos = new HashMap<>();
		emptyDatasets = new HashSet<>();
		if (dfWanted) {
			dfInfos = new HashSet<>();
		}

		try {
			for (Long dfid : dfids) {
				List<Object> dss = icat.search(sessionId,
						"SELECT ds FROM Dataset ds JOIN ds.datafiles df WHERE df.id = " + dfid
								+ " AND df.location IS NOT NULL INCLUDE ds.investigation.facility");
				if (dss.size() == 1) {
					Dataset ds = (Dataset) dss.get(0);
					long dsid = ds.getId();
					dsInfos.put(dsid, new DsInfoImpl(ds));
					if (dfWanted) {
						Datafile df = (Datafile) icat.get(sessionId, "Datafile", dfid);
						String location = IdsBean.getLocation(dfid, df.getLocation());
						dfInfos.add(
								new DfInfoImpl(dfid, df.getName(), location, df.getCreateId(), df.getModId(), dsid));
					}
				} else {
					// Next line may reveal a permissions problem
					icat.get(sessionId, "Datafile", dfid);
					throw new NotFoundException("Datafile " + dfid);
				}
			}
			for (Long dsid : dsids) {
				Dataset ds = (Dataset) icat.get(sessionId, "Dataset ds INCLUDE ds.investigation.facility", dsid);
				dsInfos.put(dsid, new DsInfoImpl(ds));
				String query = "SELECT min(df.id), max(df.id), count(df.id) FROM Datafile df WHERE df.dataset.id = "
						+ dsid + " AND df.location IS NOT NULL";
				JsonArray result = Json.createReader(new ByteArrayInputStream(restSession.search(query).getBytes()))
						.readArray().getJsonArray(0);
				if (result.getJsonNumber(2).longValueExact() == 0) { // Count 0
					emptyDatasets.add(dsid);
				} else if (dfWanted) {
					manyDfs(dsid, result);
				}
			}

			for (Long invid : invids) {
				String query = "SELECT min(ds.id), max(ds.id), count(ds.id) FROM Dataset ds WHERE ds.investigation.id = "
						+ invid;
				JsonArray result = Json.createReader(new ByteArrayInputStream(restSession.search(query).getBytes()))
						.readArray().getJsonArray(0);
				manyDss(invid, result);

			}

		} catch (IcatException_Exception e) {
			IcatExceptionType type = e.getFaultInfo().getType();
			if (type == IcatExceptionType.INSUFFICIENT_PRIVILEGES || type == IcatExceptionType.SESSION) {
				throw new InsufficientPrivilegesException(e.getMessage());
			} else if (type == IcatExceptionType.NO_SUCH_OBJECT_FOUND) {
				throw new NotFoundException(e.getMessage());
			} else {
				throw new InternalException(e.getClass() + " " + e.getMessage());
			}

		} catch (IcatException e) {
			org.icatproject.icat.client.IcatException.IcatExceptionType type = e.getType();
			if (type == org.icatproject.icat.client.IcatException.IcatExceptionType.INSUFFICIENT_PRIVILEGES
					|| type == org.icatproject.icat.client.IcatException.IcatExceptionType.SESSION) {
				throw new InsufficientPrivilegesException(e.getMessage());
			} else if (type == org.icatproject.icat.client.IcatException.IcatExceptionType.NO_SUCH_OBJECT_FOUND) {
				throw new NotFoundException(e.getMessage());
			} else {
				throw new InternalException(e.getClass() + " " + e.getMessage());
			}
		}
		/*
		 * TODO don't calculate what is not needed - however this ensures that
		 * the flag is respected
		 */
		if (!dsWanted) {
			dsInfos = null;
			emptyDatasets = null;
		}
	}

	private void manyDss(Long invid, JsonArray result)
			throws IcatException, InsufficientPrivilegesException, InternalException {
		long min = result.getJsonNumber(0).longValueExact();
		long max = result.getJsonNumber(1).longValueExact();
		long count = result.getJsonNumber(2).longValueExact();
		logger.debug("manyDss min: {} max: {} count: {}", min, max, count);
		if (count != 0) {
			if (count <= maxEntities) {
				String query = "SELECT inv.name, inv.visitId, inv.facility.id,  inv.facility.name FROM Investigation inv WHERE inv.id = "
						+ invid;
				result = Json.createReader(new ByteArrayInputStream(restSession.search(query).getBytes())).readArray();
				if (result.size() == 0) {
					return;
				}
				result = result.getJsonArray(0);
				String invName = result.getString(0);
				String visitId = result.getString(1);
				long facilityId = result.getJsonNumber(2).longValueExact();
				String facilityName = result.getString(3);

				query = "SELECT ds.id, ds.name, ds.location FROM Dataset ds WHERE ds.investigation.id = " + invid
						+ " AND ds.id BETWEEN " + min + " AND " + max;
				result = Json.createReader(new ByteArrayInputStream(restSession.search(query).getBytes())).readArray();
				for (JsonValue tupV : result) {
					JsonArray tup = (JsonArray) tupV;
					long dsid = tup.getJsonNumber(0).longValueExact();
					dsInfos.put(dsid, new DsInfoImpl(dsid, tup.getString(1), tup.getString(2, null), invid, invName,
							visitId, facilityId, facilityName));

					query = "SELECT min(df.id), max(df.id), count(df.id) FROM Datafile df WHERE df.dataset.id = "
							+ dsid + " AND df.location IS NOT NULL";
					result = Json.createReader(new ByteArrayInputStream(restSession.search(query).getBytes()))
							.readArray().getJsonArray(0);
					if (result.getJsonNumber(2).longValueExact() == 0) {
						emptyDatasets.add(dsid);
					} else if (dfWanted) {
						manyDfs(dsid, result);
					}

				}
			} else {
				long half = (min + max) / 2;
				String query = "SELECT min(ds.id), max(ds.id), count(ds.id) FROM Dataset ds WHERE ds.investigation.id = "
						+ invid + " AND ds.id BETWEEN " + min + " AND " + half;
				result = Json.createReader(new ByteArrayInputStream(restSession.search(query).getBytes())).readArray();
				manyDss(invid, result);
				query = "SELECT min(ds.id), max(ds.id), count(ds.id) FROM Dataset ds WHERE ds.investigation.id = "
						+ invid + " AND ds.id BETWEEN " + half + 1 + " AND " + max;
				result = Json.createReader(new ByteArrayInputStream(restSession.search(query).getBytes())).readArray()
						.getJsonArray(0);
				manyDss(invid, result);
			}
		}

	}

	private void manyDfs(long dsid, JsonArray result)
			throws IcatException, InsufficientPrivilegesException, InternalException {
		long min = result.getJsonNumber(0).longValueExact();
		long max = result.getJsonNumber(1).longValueExact();
		long count = result.getJsonNumber(2).longValueExact();
		logger.debug("manyDfs min: {} max: {} count: {}", min, max, count);
		if (count != 0) {
			if (count <= maxEntities) {
				String query = "SELECT df.id, df.name, df.location, df.createId, df.modId FROM Datafile df WHERE df.dataset.id = "
						+ dsid + " AND df.location IS NOT NULL AND df.id BETWEEN " + min + " AND " + max;
				result = Json.createReader(new ByteArrayInputStream(restSession.search(query).getBytes())).readArray();
				for (JsonValue tupV : result) {
					JsonArray tup = (JsonArray) tupV;
					long dfid = tup.getJsonNumber(0).longValueExact();
					String location = IdsBean.getLocation(dfid, tup.getString(2, null));
					dfInfos.add(
							new DfInfoImpl(dfid, tup.getString(1), location, tup.getString(3), tup.getString(4), dsid));
				}
			} else {
				long half = (min + max) / 2;
				String query = "SELECT min(df.id), max(df.id), count(df.id) FROM Datafile df WHERE df.dataset.id = "
						+ dsid + " AND df.location IS NOT NULL AND df.id BETWEEN " + min + " AND " + half;
				result = Json.createReader(new ByteArrayInputStream(restSession.search(query).getBytes())).readArray()
						.getJsonArray(0);
				manyDfs(dsid, result);
				query = "SELECT min(df.id), max(df.id), count(df.id) FROM Datafile df WHERE df.dataset.id = " + dsid
						+ " AND df.location IS NOT NULL AND df.id BETWEEN " + (half + 1) + " AND " + max;
				result = Json.createReader(new ByteArrayInputStream(restSession.search(query).getBytes())).readArray()
						.getJsonArray(0);
				manyDfs(dsid, result);
			}
		}

	}

	public Set<DfInfoImpl> getDfInfo() {
		return dfInfos;
	}

	public boolean mustZip() {
		return dfids.size() > 1L || !dsids.isEmpty() || !invids.isEmpty()
				|| (dfids.isEmpty() && dsids.isEmpty() && invids.isEmpty());
	}

	public boolean isSingleDataset() {
		return dfids.isEmpty() && dsids.size() == 1 && invids.isEmpty();
	}

	public Set<Long> getEmptyDatasets() {
		return emptyDatasets;
	}

}
