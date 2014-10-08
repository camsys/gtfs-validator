package com.conveyal.gtfs.validator.json;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.logging.Logger;
import java.util.zip.ZipException;

import org.onebusaway.csv_entities.exceptions.CsvEntityIOException;
import org.onebusaway.csv_entities.exceptions.MissingRequiredFieldException;
import org.onebusaway.gtfs.impl.GtfsDaoImpl;
import org.onebusaway.gtfs.model.Agency;
import org.onebusaway.gtfs.serialization.GtfsReader;
import org.onebusaway.gtfs.services.GtfsDao;

import com.conveyal.gtfs.service.GtfsValidationService;
import com.conveyal.gtfs.service.StatisticsService;
import com.conveyal.gtfs.service.impl.GtfsStatisticsService;

/**
 * Process a feed and return the validation results and the statistics.
 * @author mattwigway
 */
public class FeedProcessor {
	private File feed;
	private GtfsDao dao;
	private FeedValidationResult output;
	private static Logger _log = Logger.getLogger(FeedProcessor.class.getName());
	
	/**
	 * Create a feed processor for the given feed
	 * @param feed
	 */
	public FeedProcessor (File feed) {
		this.feed = feed;
		this.output = new FeedValidationResult();
	}
	
	/**
	 * Load the feed and run the validator and calculate statistics.
	 * @throws IOException
	 */
	public void run () throws IOException {
		load();
		if (output.loadStatus.equals(LoadStatus.SUCCESS)) {
			validate();
			calculateStats();
		}
	}
	
	/**
	 * Load the feed into memory for processing. This is generally called from {@link #run}.
	 * @throws IOException 
	 */
	public void load () throws IOException {
		_log.fine("Loading GTFS");
		
		// check if the file is accessible
		if (!feed.exists() || !feed.canRead())
			throw new IOException("File does not exist or not readable");
		
		output.feedFileName = feed.getName();
		
		// note: we have two references because a GtfsDao is not mutable and we can't load to it,
		// but a GtfsDaoImpl is.
		GtfsDaoImpl dao = new GtfsDaoImpl();
		this.dao = dao;
		GtfsReader reader = new GtfsReader();
		reader.setEntityStore(dao);
		// Exceptions here mean a problem with the file 
		try {
			reader.setInputLocation(feed);
			reader.run();
			output.loadStatus = LoadStatus.SUCCESS;
		}
		catch (ZipException e) {
			output.loadStatus = LoadStatus.INVALID_ZIP_FILE;
		}
		catch (CsvEntityIOException e) {
			Throwable cause = e.getCause();
			if (cause instanceof MissingRequiredFieldException) {
				output.loadStatus = LoadStatus.MISSING_REQUIRED_FIELD;
				output.loadFailureReason = cause.getMessage();
			}
			else {
				output.loadStatus = LoadStatus.OTHER_FAILURE;
			}
		}
		catch (IOException e) {
			output.loadStatus = LoadStatus.OTHER_FAILURE;
		}
	}
	
	/**
	 * Run the GTFS validator
	 */
	public void validate () {
		GtfsValidationService validator = new GtfsValidationService(dao);
		
		_log.fine("Validating routes");
		output.routes = validator.validateRoutes();
		_log.fine("Validating trips");
		output.trips = validator.validateTrips();
		_log.fine("Finding duplicate stops");
		output.stops = validator.duplicateStops();
		_log.fine("Checking shapes");
		output.shapes = validator.listReversedTripShapes();
	}
	
	/**
	 * Calculate statistics for the GTFS feed.
	 */
	public void calculateStats () {
		_log.fine("Calculating statistics");
		
		StatisticsService stats = new GtfsStatisticsService(dao);
		
		output.agencyCount = stats.getAgencyCount();
		output.routeCount = stats.getRouteCount();
		output.tripCount = stats.getTripCount();
		output.stopTimesCount = stats.getStopTimesCount();
		
		Date calDateStart = stats.getCalendarDateStart();
		Date calSvcStart = stats.getCalendarServiceRangeStart();
		Date calDateEnd = stats.getCalendarDateEnd();
		Date calSvcEnd = stats.getCalendarServiceRangeEnd();
		
		if (calDateStart == null && calSvcStart == null)
			// no service . . . this is bad
			output.startDate = null;
		else if (calDateStart == null)
			output.startDate = calSvcStart;
		else if (calSvcStart == null)
			output.startDate = calDateStart;
		else
			output.startDate = calDateStart.before(calSvcStart) ? calDateStart : calSvcStart;
		
		if (calDateEnd == null && calSvcEnd == null)
			// no service . . . this is bad
			output.endDate = null;
		else if (calDateEnd == null)
			output.endDate = calSvcEnd;
		else if (calSvcEnd == null)
			output.endDate = calDateEnd;
		else
			output.endDate = calDateEnd.before(calSvcEnd) ? calDateEnd : calSvcEnd;
		
		Collection<Agency> agencies = dao.getAllAgencies();
		output.agencies = new HashSet<String>(agencies.size());
		for (Agency agency : agencies) {
			output.agencies.add(agency.getName());
		}
	}
	
	public FeedValidationResult getOutput () {
		return output;
	}
}
