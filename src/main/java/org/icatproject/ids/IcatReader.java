package org.icatproject.ids;

import java.util.List;

import javax.annotation.PostConstruct;
import javax.ejb.Singleton;

import org.icatproject.EntityBaseBean;
import org.icatproject.ICAT;
import org.icatproject.IcatExceptionType;
import org.icatproject.IcatException_Exception;
import org.icatproject.Login.Credentials;
import org.icatproject.Login.Credentials.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class IcatReader {

	private ICAT icat;

	private String sessionId;

	private final static Logger logger = LoggerFactory.getLogger(IcatReader.class);

	@PostConstruct
	private void init() {
		try {
			login();
			logger.info("Reader started");
		} catch (Exception e) {
			throw new RuntimeException("Reader reports " + e.getClass() + " " + e.getMessage());
		}
	}

	private void login() throws IcatException_Exception {
		PropertyHandler propertyHandler = PropertyHandler.getInstance();
		icat = propertyHandler.getIcatService();
		List<String> creds = propertyHandler.getReader();
		if (creds != null) {

			Credentials credentials = new Credentials();
			List<Entry> entries = credentials.getEntry();
			for (int i = 1; i < creds.size(); i += 2) {
				Entry entry = new Entry();
				entry.setKey(creds.get(i));
				entry.setValue(creds.get(i + 1));
				entries.add(entry);
			}
			sessionId = icat.login(creds.get(0), credentials);
		}
	}

	public EntityBaseBean get(String query, long id) throws IcatException_Exception {
		try {
			return icat.get(sessionId, query, id);
		} catch (IcatException_Exception e) {
			if (e.getFaultInfo().getType().equals(IcatExceptionType.SESSION)) {
				login();
				return icat.get(sessionId, query, id);
			} else {
				throw e;
			}
		}
	}

}