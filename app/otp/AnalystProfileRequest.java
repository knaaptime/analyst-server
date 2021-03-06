package otp;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import models.Shapefile;

import org.opentripplanner.analyst.PointSet;
import org.opentripplanner.analyst.ResultSet;
import org.opentripplanner.analyst.ResultSetWithTimes;
import org.opentripplanner.analyst.SampleSet;
import org.opentripplanner.analyst.SurfaceCache;
import org.opentripplanner.analyst.TimeSurface;
import org.opentripplanner.api.model.TimeSurfaceShort;
import org.opentripplanner.api.param.LatLon;
import org.opentripplanner.profile.ProfileRequest;
import org.opentripplanner.profile.ProfileResponse;
import org.opentripplanner.profile.ProfileRouter;

import org.opentripplanner.profile.AnalystProfileRouterPrototype;

import utils.ResultEnvelope;

import com.google.common.collect.Lists;

import controllers.Api;

public class AnalystProfileRequest extends ProfileRequest {
	
	private static final long serialVersionUID = 1L;

	private static SurfaceCache profileResultCache = new SurfaceCache(100);

	private static  Map<String, ResultSet> resultCache = new ConcurrentHashMap<String, ResultSet>();

	public int cutoffMinutes;
	public String graphId;
	
	public static TimeSurfaceShort createSurfaces(ProfileRequest req, String graphId, int cutoffMinutes, ResultEnvelope.Which which) {
		TimeSurfaceShort ts = null;
		AnalystProfileRouterPrototype router = new AnalystProfileRouterPrototype(Api.analyst.getGraph(graphId), req);

		try {

			TimeSurface.RangeSet result = router.route();

			result.min.cutoffMinutes = cutoffMinutes;
			result.max.cutoffMinutes = cutoffMinutes;
			result.avg.cutoffMinutes = cutoffMinutes;
            
            // add both the min surface and the max surface to the cache; they will be retrieved later on by ID
            profileResultCache.add(result.min);
            profileResultCache.add(result.max);
            profileResultCache.add(result.avg);

			if (which == ResultEnvelope.Which.WORST_CASE) {
				ts = new TimeSurfaceShort(result.max);
			}
			else if (which == ResultEnvelope.Which.BEST_CASE) {
				ts = new TimeSurfaceShort(result.min);
			}
			else if (which == ResultEnvelope.Which.AVERAGE) {
				ts = new TimeSurfaceShort(result.avg);
			}
        }
        catch (Exception e) {
        	e.printStackTrace();
        }
        finally {
			router.cleanup(); // destroy routing contexts even when an exception happens
        }

		return ts;

  	}
	
	/**
	 * Get the ResultSet for the given ID. Note that no ResultEnvelope.Which need be specified as each surface ID is unique to a particular
	 * statistic.
	 */
	public static ResultSet getResult(Integer surfaceId, String shapefileId) {
		
		String resultId = "resultId_" + surfaceId + "_" + shapefileId;
    	
		ResultSet result;
    	
    	synchronized(resultCache) {
    		if(resultCache.containsKey(resultId))
    			result = resultCache.get(resultId);
        	else {
        		TimeSurface surf =getSurface(surfaceId);
        		
        		PointSet ps = Shapefile.getShapefile(shapefileId).getPointSet();
        		SampleSet ss = ps.getSampleSet(Api.analyst.getGraph(surf.routerId));
        		result = new ResultSet(ss, surf);

        		resultCache.put(resultId, result);
        	}
    	}
    	
    	return result;
	}
	
	/**
	 * Get the ResultSet for the given ID. Note that no min/max need be specified as each surface ID is unique to a particular
	 * statistic.
	 */
	public static ResultSetWithTimes getResultWithTimes(Integer surfaceId, String shapefileId) {
		
		String resultId = "resultWithTimesId_" + surfaceId + "_" + shapefileId;
    	
		ResultSetWithTimes resultWithTimes;
    	
    	synchronized(resultCache) {
    		if(resultCache.containsKey(resultId))
    			resultWithTimes = (ResultSetWithTimes)resultCache.get(resultId);
        	else {
        		TimeSurface surf = getSurface(surfaceId);
        			
        		PointSet ps = Shapefile.getShapefile(shapefileId).getPointSet();
        		SampleSet ss = ps.getSampleSet(Api.analyst.getGraph(surf.routerId));
        		resultWithTimes = new ResultSetWithTimes(ss, surf);
        		resultCache.put(resultId, resultWithTimes);
        	}
    	}
    	
    	return resultWithTimes;
	}
	
	public static TimeSurface getSurface(Integer surfaceId) {
		return profileResultCache.get(surfaceId);
	}

}
