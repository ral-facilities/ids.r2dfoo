package org.icatproject.ids;

import java.io.ByteArrayOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.json.Json;
import jakarta.json.stream.JsonGenerator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.icatproject.ids.exceptions.BadRequestException;
import org.icatproject.ids.exceptions.DataNotOnlineException;
import org.icatproject.ids.exceptions.InsufficientPrivilegesException;
import org.icatproject.ids.exceptions.InternalException;
import org.icatproject.ids.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/")
@Stateless
public class IdsService {

	private static final  Logger logger = LoggerFactory.getLogger(IdsService.class);

	@EJB
	private IdsBean idsBean;

	private Pattern rangeRe;

	@PostConstruct
	private void init() {
		logger.info("creating IdsService");
		rangeRe = Pattern.compile("bytes=(\\d+)-");
		logger.info("created IdsService");
	}

	@PreDestroy
	private void exit() {
		logger.info("destroyed IdsService");
	}

	/**
	 * Should return "IdsOK"
	 * 
	 * @summary ping
	 * 
	 * @return "IdsOK"
	 * 
	 * @statuscode 200 To indicate success
	 */
	@GET
	@Path("ping")
	@Produces(MediaType.TEXT_PLAIN)
	public String ping() {
		logger.debug("ping request received");
		return "IdsOK";
	}

	/**
	 * Return the version of the server
	 * 
	 * @summary version
	 * 
	 * @return json string of the form: <samp>{"version":"4.4.0"}</samp>
	 */
	@GET
	@Path("version")
	@Produces(MediaType.APPLICATION_JSON)
	public String getVersion() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		JsonGenerator gen = Json.createGenerator(baos);
		gen.writeStartObject().write("version", Constants.API_VERSION).writeEnd();
		gen.close();
		return baos.toString();
	}

	/**
	 * This version of the IDS is read only so this method always returns true.
	 * 
	 * @summary isReadOnly
	 * 
	 * @return true
	 * 
	 * @statuscode 200 To indicate success
	 */
	@GET
	@Path("isReadOnly")
	@Produces(MediaType.TEXT_PLAIN)
	public boolean isReadOnly(@Context HttpServletRequest request) {
		return true;
	}

	/**
	 * This version of the IDS uses two level storage (main and archive) so 
	 * this method always returns true.
	 * 
	 * @summary isTwoLevel
	 * 
	 * @return true
	 * 
	 * @statuscode 200 To indicate success
	 */
	@GET
	@Path("isTwoLevel")
	@Produces(MediaType.TEXT_PLAIN)
	public boolean isTwoLevel(@Context HttpServletRequest request) {
		return true;
	}

	/**
	 * Return the url of the icat.server that this IDS server has been
	 * configured to use. This is the icat.server from which a sessionId must be
	 * obtained.
	 * 
	 * @return the url of the icat server
	 * 
	 * @statuscode 200 To indicate success
	 */
	@GET
	@Path("getIcatUrl")
	@Produces(MediaType.TEXT_PLAIN)
	public String getIcatUrl(@Context HttpServletRequest request) {
		return idsBean.getIcatUrl();
	}

	/**
	 * Return data files included in the preparedId returned by a call to
	 * prepareData.
	 * 
	 * @summary getData
	 * @param preparedId
	 *            A valid preparedId returned by a call to prepareData
	 * @param outname
	 *            The file name to put in the returned header
	 *            "ContentDisposition". If it does not end in .zip but it is a
	 *            zip file then a ".zip" will be appended.
	 * @param range
	 *            A range header which must match "bytes=(\\d+)-" to specify an
	 *            offset i.e. to skip a number of bytes.
	 * 
	 * @return a stream of json data.
	 * 
	 * @throws BadRequestException
	 * @throws NotFoundException
	 * @throws InternalException
	 * @throws InsufficientPrivilegesException
	 * @throws DataNotOnlineException
	 * 
	 * @statuscode 200 To indicate success
	 */
	@GET
	@Path("getData")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response getData(@Context HttpServletRequest request, @QueryParam("preparedId") String preparedId,
			@QueryParam("outname") String outname, @HeaderParam("Range") String range) throws BadRequestException,
			NotFoundException, InternalException, DataNotOnlineException {
		long offset = 0;
		if (range != null) {

			Matcher m = rangeRe.matcher(range);
			if (!m.matches()) {
				throw new BadRequestException("The range must match " + rangeRe.pattern());
			}
			offset = Long.parseLong(m.group(1));
			logger.debug("Range {} -> offset {}", range, offset);
		}
		return idsBean.getData(preparedId, outname, offset);
	}

	/**
	 * Return list of id values of data files included in the preparedId
	 * returned by a call to prepareData.
	 * 
	 * @summary getDatafileIds
	 * @param preparedId
	 *            A valid preparedId returned by a call to prepareData
	 * 
	 * @return a list of id values
	 * 
	 * @throws BadRequestException
	 * @throws InternalException
	 * @throws NotFoundException
	 * 
	 * @statuscode 200 To indicate success
	 */
	@GET
	@Path("getDatafileIds")
	@Produces(MediaType.APPLICATION_JSON)
	public String getDatafileIds(@Context HttpServletRequest request, @QueryParam("preparedId") String preparedId)
			throws BadRequestException, InternalException, NotFoundException {
		return idsBean.getDatafileIds(preparedId);
	}

	/**
	 * Obtain detailed information about what the ids is doing. You need to be
	 * a user listed in the rootUserNames config property to use this call.
	 * 
	 * @summary getServiceStatus
	 * 
	 * @param sessionId
	 *            A valid ICAT session ID of a user in the IDS rootUserNames
	 *            set.
	 * 
	 * @return a json string.
	 * 
	 * @throws InternalException
	 * @throws InsufficientPrivilegesException
	 * 
	 * @statuscode 200 To indicate success
	 */
	@GET
	@Path("getServiceStatus")
	@Produces(MediaType.APPLICATION_JSON)
	public String getServiceStatus(@Context HttpServletRequest request, @QueryParam("sessionId") String sessionId)
			throws InternalException, InsufficientPrivilegesException {
		return idsBean.getServiceStatus(sessionId);
	}

	/**
	 * Return the total size of all the data files specified by the
	 * investigationIds, datasetIds and datafileIds along with a sessionId.
	 * 
	 * @summary getSize
	 * 
	 * @param preparedId
	 *            A valid preparedId returned by a call to prepareData
	 * @param sessionId
	 *            A sessionId returned by a call to the icat server.
	 * @param investigationIds
	 *            If present, a comma separated list of investigation id values
	 * @param datasetIds
	 *            If present, a comma separated list of data set id values or
	 *            null
	 * @param datafileIds
	 *            If present, a comma separated list of data file id values.
	 * 
	 * @return the size in bytes
	 * 
	 * @throws BadRequestException
	 * @throws NotFoundException
	 * @throws InsufficientPrivilegesException
	 * @throws InternalException
	 * 
	 * @statuscode 200 To indicate success
	 */
	@GET
	@Path("getSize")
	@Produces(MediaType.TEXT_PLAIN)
	public long getSize(@Context HttpServletRequest request, @QueryParam("preparedId") String preparedId,
			@QueryParam("sessionId") String sessionId, @QueryParam("investigationIds") String investigationIds,
			@QueryParam("datasetIds") String datasetIds, @QueryParam("datafileIds") String datafileIds)
			throws BadRequestException, NotFoundException, InsufficientPrivilegesException, InternalException {
		if (preparedId != null) {
			return idsBean.getSize(preparedId);
		} else {
			return idsBean.getSize(sessionId, investigationIds, datasetIds, datafileIds);
		}
	}

	/**
	 * Returns true if all the data files are ready to be downloaded. As a side
	 * effect, if any data files are archived and no restoration has been
	 * requested then a restoration of those data files will be launched.
	 * 
	 * To make this operation fast, if anything is not found on line then the
	 * rest of the work is done in the background and the status returned. At a
	 * subsequent call it will restart from where it failed and if it gets to
	 * the end successfully will then go through all those it did not look at
	 * because it did not start at the beginning.
	 * 
	 * @summary isPrepared
	 * 
	 * @param preparedId
	 *            A valid preparedId returned by a call to prepareData
	 * 
	 * @return true if all the data files are ready to be downloaded else false.
	 * 
	 * @throws BadRequestException
	 * @throws NotFoundException
	 * @throws InternalException
	 * 
	 * @statuscode 200 To indicate success
	 */
	@GET
	@Path("isPrepared")
	@Produces(MediaType.TEXT_PLAIN)
	public boolean isPrepared(@Context HttpServletRequest request, @QueryParam("preparedId") String preparedId)
			throws BadRequestException, NotFoundException, InternalException {
		return idsBean.isPrepared(preparedId);
	}

	/**
	 * Return a String describing the the percentage of files that have been
	 * restored for the given prepared ID. This will normally be an integer 
	 * value between 0 and 100 but could also be a status value such as 
	 * "UNKNOWN".
	 * 
	 * @summary getPercentageComplete
	 * 
	 * @param preparedId
	 *            A valid preparedId returned by a call to prepareData
	 *
	 * @return a String describing the completeness of the restore request
	 *
	 * @throws NotFoundException
	 * @throws InternalException
	 * 
	 * @statuscode 200 To indicate success
	 */
	@GET
	@Path("getPercentageComplete")
	@Produces(MediaType.TEXT_PLAIN)
	public String getPercentageComplete(@Context HttpServletRequest request, @QueryParam("preparedId") String preparedId)
			throws NotFoundException, InternalException {
		return idsBean.getPercentageComplete(preparedId);
	}

	/**
	 * Cancel the restore request linked to the given prepared ID.
	 * 
	 * @summary cancel
	 * 
	 * @param preparedId
	 *            A valid preparedId returned by a call to prepareData
	 *
	 * @throws NotFoundException
	 * 
	 * @statuscode 200 To indicate success
	 */
	@GET
	@Path("cancel")
	public void cancel(@Context HttpServletRequest request, @QueryParam("preparedId") String preparedId)
			throws NotFoundException {
		idsBean.cancel(preparedId);
	}

	/**
	 * Prepare data files for subsequent download. The data files are specified
	 * by the investigationIds, datasetIds and datafileIds, any of which may be
	 * omitted, along with a sessionId.
	 * 
	 * @summary prepareData
	 * 
	 * @param sessionId
	 *            A sessionId returned by a call to the icat server.
	 * @param investigationIds
	 *            A comma separated list of investigation id values.
	 * @param datasetIds
	 *            A comma separated list of data set id values.
	 * @param datafileIds
	 *            A comma separated list of datafile id values.
	 * @param compress
	 *            If true use default compression otherwise no compression. This
	 *            only applies if preparedId is not set and if the results are
	 *            being zipped.
	 * @param zip
	 *            If true the data should be zipped. If multiple files are
	 *            requested (or could be because a datasetId or investigationId
	 *            has been specified) the data are zipped regardless of the
	 *            specification of this flag.
	 * 
	 * @return a string with the preparedId
	 * 
	 * @throws BadRequestException
	 * @throws InsufficientPrivilegesException
	 * @throws NotFoundException
	 * @throws InternalException
	 * 
	 * @statuscode 200 To indicate success
	 */
	@POST
	@Path("prepareData")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.TEXT_PLAIN)
	public String prepareData(@Context HttpServletRequest request, @FormParam("sessionId") String sessionId,
			@FormParam("investigationIds") String investigationIds, @FormParam("datasetIds") String datasetIds,
			@FormParam("datafileIds") String datafileIds, @FormParam("compress") boolean compress,
			@FormParam("zip") boolean zip)
			throws BadRequestException, InsufficientPrivilegesException, NotFoundException, InternalException {
		return idsBean.prepareData(sessionId, investigationIds, datasetIds, datafileIds, compress, zip);
	}

}
