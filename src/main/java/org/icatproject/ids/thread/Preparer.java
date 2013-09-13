package org.icatproject.ids.thread;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

import org.icatproject.Dataset;
import org.icatproject.ids.entity.IdsDataEntity;
import org.icatproject.ids.storage.StorageFactory;
import org.icatproject.ids.storage.StorageInterface;
import org.icatproject.ids.util.RequestHelper;
import org.icatproject.ids.util.RequestQueues;
import org.icatproject.ids.util.RequestedState;
import org.icatproject.ids.util.StatusInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Prepares zips for users to download using /getData
 */
public class Preparer implements Runnable {

	private final static Logger logger = LoggerFactory.getLogger(ProcessQueue.class);

	private IdsDataEntity de;
	private RequestQueues requestQueues;
	private RequestHelper requestHelper;

	public Preparer(IdsDataEntity de, RequestHelper requestHelper) {
		this.de = de;
		this.requestQueues = RequestQueues.getInstance();
		this.requestHelper = requestHelper;
	}
	
	@Override
	public void run() {
		logger.info("starting preparer");
		Map<IdsDataEntity, RequestedState> deferredOpsQueue = requestQueues.getDeferredOpsQueue();
		Set<Dataset> changing = requestQueues.getChanging();
		StorageInterface fastStorageInterface = StorageFactory.getInstance().createFastStorageInterface();
		StorageInterface slowStorageInterface = StorageFactory.getInstance().createSlowStorageInterface();
		
		// if one of the previous DataEntities of the Request failed, there's no point continuing with this one
		if (de.getRequest().getStatus() == StatusInfo.INCOMPLETE) {
			synchronized (deferredOpsQueue) {
				requestHelper.setDataEntityStatus(de, StatusInfo.INCOMPLETE);
			}
		}		
		// if this is the first DE of the Request being processed, set the Request status to RETRIVING
		if (de.getRequest().getStatus() == StatusInfo.SUBMITTED) {
			synchronized (deferredOpsQueue) {
				requestHelper.setRequestStatus(de.getRequest(), StatusInfo.RETRIVING);
			}
		}
		StatusInfo resultingStatus = StatusInfo.COMPLETED; // let's assume that everything will go OK		
		// restore the dataset if needed
		try {
			if (!fastStorageInterface.datasetExists(de.getIcatDataset())) {
				InputStream is = slowStorageInterface.getDatasetInputStream(de.getIcatDataset());
				fastStorageInterface.putDataset(de.getIcatDataset(), is);
			}
		} catch (FileNotFoundException e) {
			logger.warn("Could not restore " + de.getIcatDataset() + " (file doesn't exist): " + e.getMessage());
			resultingStatus = StatusInfo.NOT_FOUND;
		} catch (Exception e) {
			logger.warn("Could not restore " + de.getIcatDataset() + " (reason uknonwn): " + e.getMessage());
			resultingStatus = StatusInfo.ERROR;
		}
		
		synchronized (deferredOpsQueue) {
			logger.info(String.format("Changing status of %s to %s", de, resultingStatus));
			requestHelper.setDataEntityStatus(de, resultingStatus);
			changing.remove(de.getIcatDataset());
		}
		
		// if it's the last DataEntity of the Request and all of them were successful
		if (de.getRequest().getStatus() == StatusInfo.COMPLETED) {
			String preparedZipName = de.getRequest().getPreparedId() + ".zip";
			try {
				fastStorageInterface.prepareZipForRequest(de.getRequest().getIcatDatasets(), 
						de.getRequest().getIcatDatafiles(), preparedZipName, de.getRequest().isCompress());
			} catch (Exception e) {
				logger.warn(String.format("Could not prepare the zip. Reason: " + e.getMessage()));
				synchronized (deferredOpsQueue) {
					logger.info(String.format("Changing status of %s to %s", de, StatusInfo.ERROR));
					requestHelper.setDataEntityStatus(de, resultingStatus);
					changing.remove(de.getIcatDataset());
				}
			}
		}
	}

}