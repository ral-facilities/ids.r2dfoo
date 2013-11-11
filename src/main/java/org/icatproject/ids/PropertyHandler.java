package org.icatproject.ids;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.namespace.QName;

import org.icatproject.ICAT;
import org.icatproject.ICATService;
import org.icatproject.IcatException_Exception;
import org.icatproject.ids.plugin.ArchiveStorageInterface;
import org.icatproject.ids.plugin.MainStorageInterface;
import org.icatproject.utils.CheckedProperties;
import org.icatproject.utils.CheckedProperties.CheckedPropertyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Load the properties specified in the properties file ids.properties.
 */
public class PropertyHandler {

	private static final Logger logger = LoggerFactory.getLogger(PropertyHandler.class);
	private static PropertyHandler instance = null;

	private String icatUrl;
	private long writeDelaySeconds;
	private long processQueueIntervalSeconds;
	private MainStorageInterface mainStorage;
	private ArchiveStorageInterface archiveStorage;
	private Path cacheDir;
	private ICAT icatService;
	private int preparedCount;
	private Set<String> rootUserNames;
	private List<String> reader;
	private boolean readOnly;
	private long preparedCacheSizeBytes;
	public static Logger getLogger() {
		return logger;
	}

	public String getIcatUrl() {
		return icatUrl;
	}

	public long getPreparedCacheSizeBytes() {
		return preparedCacheSizeBytes;
	}

	public long getSizeCheckIntervalMillis() {
		return sizeCheckIntervalMillis;
	}

	public boolean isCompressDatasetCache() {
		return compressDatasetCache;
	}

	public boolean isTolerateWrongCompression() {
		return tolerateWrongCompression;
	}

	public long getDatasetCacheSizeBytes() {
		return datasetCacheSizeBytes;
	}

	private long sizeCheckIntervalMillis;
	private boolean compressDatasetCache;
	private boolean tolerateWrongCompression;
	private long datasetCacheSizeBytes;

	public Set<String> getRootUserNames() {
		return rootUserNames;
	}

	public List<String> getReader() {
		return reader;
	}

	@SuppressWarnings("unchecked")
	private PropertyHandler() {

		CheckedProperties props = new CheckedProperties();

		try {
			props.loadFromFile("ids.properties");
			logger.info("Property file ids.properties loaded");

			// do some very basic error checking on the config options
			icatUrl = props.getProperty("icat.url");
			if (!icatUrl.endsWith("ICATService/ICAT?wsdl")) {
				if (icatUrl.charAt(icatUrl.length() - 1) == '/') {
					icatUrl = icatUrl + "ICATService/ICAT?wsdl";
				} else {
					icatUrl = icatUrl + "/ICATService/ICAT?wsdl";
				}
			}

			try {
				URL url = new URL(icatUrl);
				icatService = new ICATService(url, new QName("http://icatproject.org",
						"ICATService")).getICATPort();
			} catch (MalformedURLException e) {
				String msg = "Invalid property icat.url (" + icatUrl + "). Check URL format";
				logger.error(msg);
				throw new IllegalStateException(msg);
			}
			try {
				icatService.getApiVersion();// make a test call
			} catch (IcatException_Exception e) {
				String msg = "Problem finding ICAT API version " + e.getFaultInfo().getType() + " "
						+ e.getMessage();
				logger.error(msg);
				throw new IllegalStateException(msg);
			}

			preparedCount = props.getPositiveInt("preparedCount");
			processQueueIntervalSeconds = props.getPositiveLong("processQueueIntervalSeconds");
			rootUserNames = new HashSet<>(Arrays.asList(props.getString("rootUserNames").trim()
					.split("\\s+")));
			reader = Arrays.asList(props.getString("reader").trim().split("\\s+"));
			if (reader.size() % 2 != 1) {
				throw new IllegalStateException("reader must have an odd number of words");
			}
			readOnly = props.containsKey("readOnly");
			preparedCacheSizeBytes = props.getPositiveLong("preparedCacheSize1024bytes") * 1024;
			sizeCheckIntervalMillis = props.getPositiveInt("sizeCheckIntervalSeconds") * 1000L;

			try {
				Class<MainStorageInterface> klass = (Class<MainStorageInterface>) Class
						.forName(props.getString("plugin.main.class"));
				mainStorage = klass.getConstructor(File.class).newInstance(
						props.getFile("plugin.main.properties"));
				logger.debug("mainStorage initialised");
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause();
				abort(cause.getClass() + " " + cause.getMessage());
			} catch (Exception e) {
				abort(e.getClass() + " " + e.getMessage());
			}

			if (props.getProperty("plugin.archive.class") == null) {
				logger.info("Property plugin.archive.class not set, single storage enabled.");
			} else {
				writeDelaySeconds = props.getPositiveLong("writeDelaySeconds");
				compressDatasetCache = props.contains("compressDatasetCache");
				tolerateWrongCompression = props.contains("tolerateWrongCompression");
				datasetCacheSizeBytes = props.getPositiveLong("datasetCacheSize1024bytes") * 1024;

				try {
					Class<ArchiveStorageInterface> klass = (Class<ArchiveStorageInterface>) Class
							.forName(props.getString("plugin.archive.class"));
					archiveStorage = klass.getConstructor(File.class).newInstance(
							props.getFile("plugin.archive.properties"));
					logger.debug("archiveStorage initialised");
				} catch (InvocationTargetException e) {
					Throwable cause = e.getCause();
					abort(cause.getClass() + " " + cause.getMessage());
				} catch (Exception e) {
					abort(e.getClass() + " " + e.getMessage());
				}
			}

			cacheDir = props.getFile("cache.dir").toPath();
			if (!Files.isDirectory(cacheDir)) {
				abort(cacheDir + " must be an existing directory");
			}

		} catch (CheckedPropertyException e) {
			abort(e.getMessage());
		}
	}

	private void abort(String msg) {
		logger.error(msg);
		logger.error("IllegalStateException being thrown");
		throw new IllegalStateException(msg);
	}

	public synchronized static PropertyHandler getInstance() {
		if (instance == null) {
			instance = new PropertyHandler();
		}
		return instance;
	}

	public long getWriteDelaySeconds() {
		return writeDelaySeconds;
	}

	public long getProcessQueueIntervalSeconds() {
		return processQueueIntervalSeconds;
	}

	public MainStorageInterface getMainStorage() {
		return mainStorage;
	}

	public ArchiveStorageInterface getArchiveStorage() {
		return archiveStorage;
	}

	public Path getCacheDir() {
		return cacheDir;
	}

	public ICAT getIcatService() {
		return icatService;
	}

	public int getPreparedCount() {
		return preparedCount;
	}

	public boolean getReadOnly() {
		return readOnly;
	}
}