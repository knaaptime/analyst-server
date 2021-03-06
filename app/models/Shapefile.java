package models;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.smartcardio.ATR;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.mapdb.Fun;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.PropertyType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opentripplanner.analyst.EmptyPolygonException;
import org.opentripplanner.analyst.PointFeature;
import org.opentripplanner.analyst.PointSet;
import org.opentripplanner.analyst.UnsupportedGeometryException;
import org.opentripplanner.analyst.core.Sample;

import play.Logger;
import play.Play;
import play.libs.Akka;

import com.conveyal.otpac.PointSetDatastore;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.geom.prep.PreparedPolygon;
import com.vividsolutions.jts.index.strtree.STRtree;

import controllers.Api;
import controllers.Application;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;
import utils.Bounds;
import utils.DataStore;
import utils.HaltonPoints;
import utils.HashUtils;

/**
 * A Shapefile corresponds to an OTP PointSet. All numeric Shapefile columns are converted to pointset columns and accessibility values are calculated for each.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public class Shapefile implements Serializable {
	// this should remain constant unless we make a change where we explicitly want to break deserialization
	// so that users have to start fresh.
	private static final long serialVersionUID = 2L;

	@JsonIgnore
	static private DataStore<Shapefile> shapefilesData = new DataStore<Shapefile>("shapes", true);

	public String id;
	public String name;
	
	/**
	 * The name of this shapefile in the pointset. Don't change.
	 */
	public String categoryId;
	
	public String description;
	
	public String filename;
	
	public String type;

	public String projectId;
	
	public Bounds bounds;

	@JsonIgnore
	public HashMap<String,Attribute> attributes = new HashMap<String,Attribute>();

	/** the pointset for this shapefile */
	private transient PointSet pointSet;

	@JsonIgnore
	public File file;

	@JsonIgnore
	transient private DataStore<ShapeFeature> shapeFeatures;

	@JsonIgnore
	transient private STRtree spatialIndex;

	public Shapefile() {
		
	}
	static public class ShapeFeature implements Serializable, Comparable<ShapeFeature> {

		private static final long serialVersionUID = 1L;
		public String id;
		public Geometry geom;

		@JsonIgnore
		transient private List<PreparedPolygon> preparedPolygons;

		@JsonIgnore
		transient private Map<String,HaltonPoints> haltonPointMap;

		@JsonIgnore
		public List<PreparedPolygon> getPreparedPolygons() {

			if(preparedPolygons == null) {
				preparedPolygons = new ArrayList<PreparedPolygon>();

				if(geom instanceof Polygon) {

					PreparedPolygon pp = new PreparedPolygon((Polygon)geom);
					preparedPolygons.add(pp);

				}
				else if(geom instanceof MultiPolygon) {

					for(int i = 0; i < ((MultiPolygon)geom).getNumGeometries(); i++) {
						Polygon p = (Polygon)((MultiPolygon)geom).getGeometryN(i);

						PreparedPolygon pp = new PreparedPolygon(p);
						preparedPolygons.add(pp);
					}

				}
			}

			return preparedPolygons;
		}

		@JsonIgnore
		public HaltonPoints getHaltonPoints(String attributeId) {

			if(haltonPointMap == null)
				haltonPointMap = new ConcurrentHashMap<String,HaltonPoints>();

			if(!haltonPointMap.containsKey(attributeId)) {
				HaltonPoints hp;
				if(attributes.containsKey(attributeId))
					hp = new HaltonPoints(geom, (Integer)attributes.get(attributeId));
				else
					hp = new HaltonPoints(geom, 0);

				haltonPointMap.put(attributeId, hp);
			}

			return haltonPointMap.get(attributeId);

		}

		@JsonIgnore
		public Integer getAttribute(String attributeId) {
			if(attributes.containsKey(attributeId))
				return (Integer)attributes.get(attributeId);
			else
				return 0;
		}

		@JsonIgnore
		public Long getAttributeSum(List<String> attributeIds) {

			Long sum = 0l;

			for(String attributeId : attributeIds) {
				if(attributes.containsKey(attributeId))
					sum += (Integer)attributes.get(attributeId);
			}

			return sum;

		}

		@JsonIgnore
		transient private Map<String,Sample> graphSampleMap;

		@JsonIgnore
		public Sample getSampe(String graphId) {

			if(graphSampleMap == null)
				graphSampleMap = new ConcurrentHashMap<String,Sample>();

			if(!graphSampleMap.containsKey(graphId)) {
				Point p = geom.getCentroid();
				Sample s = Api.analyst.getSample(graphId, p.getX(), p.getY());

				if(s != null)
					graphSampleMap.put(graphId, s);
			}

			return graphSampleMap.get(graphId);
		}

		Map<String,Object> attributes = new HashMap<String,Object>();

		@Override
		public int compareTo(ShapeFeature o) {
			return this.id.compareTo(o.id);
		}
	}

	public static class FeatureTime {

		public ShapeFeature feature;
		public Long time;

		public FeatureTime(ShapeFeature sf, Long t) {
			feature = sf;
			time = t;
		}
	}
	
	@JsonIgnore
	public synchronized STRtree getSpatialIndex() {
		if(spatialIndex == null)
			buildIndex();

		return spatialIndex;
	}

	/**
	 * Get the pointset.
	 */
	@JsonIgnore
	public synchronized PointSet getPointSet() {
		if (pointSet != null)
			return pointSet;

		pointSet = new PointSet(getFeatureCount());

		pointSet.id = categoryId;
		pointSet.label = this.name;
		pointSet.description = this.description;

		int index = 0;
		for(ShapeFeature sf :  this.getShapeFeatureStore().getAll()) {

			HashMap<String,Integer> propertyData = new HashMap<String,Integer>();

			for (Attribute a : this.attributes.values()) {
				String propertyId = categoryId + "." + a.fieldName;
				propertyData.put(propertyId, sf.getAttribute(a.fieldName));
				// TODO: update names when attribute name is edited.
				pointSet.setLabel(propertyId, a.name);
			}


			PointFeature pf;
			try {
				pf = new PointFeature(sf.id.toString(), sf.geom, propertyData);
				pointSet.addFeature(pf, index);
			} catch (EmptyPolygonException | UnsupportedGeometryException e) {
				e.printStackTrace();
			}


			index++;
		}

		pointSet.setLabel(categoryId, this.name);

		return pointSet;
	}

	/**
	 * Write the shapefile to the cluster cache and to S3.
	 */
	public String writeToClusterCache() throws IOException {

		PointSet ps = this.getPointSet();
		String cachePointSetId = id + ".json";

		File f = new File(cachePointSetId);

		FileOutputStream fos = new FileOutputStream(f);
		ps.writeJson(fos, true);
		fos.close();

		String s3credentials = Play.application().configuration().getString("cluster.s3credentials");
		String bucket = Play.application().configuration().getString("cluster.pointsets-bucket");
		boolean workOffline = Play.application().configuration().getBoolean("cluster.work-offline");
		
		PointSetDatastore datastore = new PointSetDatastore(10, s3credentials, workOffline, bucket);

		datastore.addPointSet(f, cachePointSetId);

		f.delete();

		return cachePointSetId;

	}



	@JsonIgnore
	public void setShapeFeatureStore(List<Fun.Tuple2<String,ShapeFeature>> features) {

		shapeFeatures = new DataStore<ShapeFeature>(getShapeDataPath(), id, features);

	}

	@JsonIgnore
	public DataStore<ShapeFeature> getShapeFeatureStore() {

		if(shapeFeatures == null){
			shapeFeatures = new DataStore<ShapeFeature>(getShapeDataPath(), id);
		}

		return shapeFeatures;

	}

	@JsonIgnore
	private static File getShapeDataPath() {
		File shapeDataPath = new File(Application.dataPath, "shape_data");

		shapeDataPath.mkdirs();

		return shapeDataPath;
	}

	@JsonIgnore
	private File getTempShapeDirPath() {

		File shapeDirPath = new File(getShapeDataPath(), "tmp_" + id);

		shapeDirPath.mkdirs();

		return shapeDirPath;
	}

	public Integer getFeatureCount() {
		return getShapeFeatureStore().size();
	}

	private void buildIndex() {
		Logger.info("building index for shapefile " + this.id);

		// it's not possible to make an R-tree with only one node, so we make an r-tree with two
		// nodes and leave one empty.
		spatialIndex = new STRtree(Math.max(getShapeFeatureStore().size(), 2));

		for(ShapeFeature feature : getShapeFeatureStore().getAll()) {
			spatialIndex.insert(feature.geom.getEnvelopeInternal(), feature);
		}
	}

	public List<ShapeFeature> query(Envelope env) {

		return getSpatialIndex().query(env);

	}
	
	public List<Attribute> getShapeAttributes() {
		return new ArrayList(attributes.values());
	}

	public void setShapeAttributes(List<Attribute> shapeAttributes) {
		for(Attribute a : shapeAttributes) {
			this.attributes.put(a.fieldName, a);
		}
	}
	
	public Collection<ShapeFeature> queryAll() {

		return shapeFeatures.getAll();

	}

	/**
	 * Create a new shapefile with the given name.
	 */
	public static Shapefile create(File originalShapefileZip, String projectId, String name) throws ZipException, IOException {

		String shapefileHash = HashUtils.hashFile(originalShapefileZip);

		String shapefileId = projectId + "_" + shapefileHash;

		if(shapefilesData.getById(shapefileId) != null) {

			Logger.info("loading shapefile " + shapefileId);

			originalShapefileZip.delete();
			return shapefilesData.getById(shapefileId);
		}

		Logger.info("creating shapefile " + shapefileId);

		Shapefile shapefile = new Shapefile();

		shapefile.id = shapefileId;
		shapefile.projectId = projectId;
		
		shapefile.name = name;
		shapefile.categoryId = Attribute.convertNameToId(name);


		ZipFile zipFile = new ZipFile(originalShapefileZip);

	    Enumeration<? extends ZipEntry> entries = zipFile.entries();

	    Boolean hasShp = false;
	    Boolean hasDbf = false;
	    
	    while(entries.hasMoreElements()) {

	        ZipEntry entry = entries.nextElement();

	        if(entry.getName().toLowerCase().endsWith("shp")){
	   	        hasShp = true;
	   	        shapefile.filename = entry.getName();
	        }
	   	    if(entry.getName().toLowerCase().endsWith("dbf"))
	        	hasDbf = true;
	    }

	    zipFile.close();

	    if(hasShp && hasDbf) {
	    	// move shape to perm location

	    	shapefile.file = new File(Shapefile.getShapeDataPath(),  shapefileId + ".zip");
	    	FileUtils.copyFile(originalShapefileZip, shapefile.file);

	    	Logger.info("loading shapefile " + shapefileId);
	    	List<Fun.Tuple2<String,ShapeFeature>> features = shapefile.getShapeFeatures();
	    	Logger.info("saving " + features.size() + " features...");

	    	shapefile.setShapeFeatureStore(features);

	    	shapefile.save();
	    		    	
	    	Logger.info("done loading shapefile " + shapefileId);
	    }
	    else
	    	shapefile = null;

	    originalShapefileZip.delete();

		return shapefile;
	}
	
	
	public void updateAttributeStats(String name, Object value) {
		
		Attribute attribute; 
		
		if(!attributes.containsKey(name)){
			attribute = new Attribute();
			attribute.name = name;
			attribute.fieldName = name;
			
			
			attributes.put(name, attribute);
		}
		else
			attribute = attributes.get(name);
		
		if(value != null && value instanceof Number )
			attribute.numeric = true;
		
		attribute.updateStats(value);
		 
	}

	private List<Fun.Tuple2<String,ShapeFeature>> getShapeFeatures() throws ZipException, IOException {

		List<Fun.Tuple2<String,ShapeFeature>> features = new ArrayList<Fun.Tuple2<String,ShapeFeature>>();

		File unzippedShapefile = getUnzippedShapefile();

		Map map = new HashMap();
		map.put( "url", unzippedShapefile.toURI().toURL() );

		org.geotools.data.DataStore dataStore = DataStoreFinder.getDataStore(map);

		SimpleFeatureSource featureSource = dataStore.getFeatureSource(dataStore.getTypeNames()[0]);

		SimpleFeatureType schema = featureSource.getSchema();

		CoordinateReferenceSystem shpCRS = schema.getCoordinateReferenceSystem();
		MathTransform transform;

		try {
			transform = CRS.findMathTransform(shpCRS, DefaultGeographicCRS.WGS84, true);
		} catch (FactoryException e1) {
			e1.printStackTrace();
			return features;
		}

		SimpleFeatureCollection collection = featureSource.getFeatures();
		SimpleFeatureIterator iterator = collection.features();

		int skippedFeatures = 0;

		Set<String> fieldnamesFound = new HashSet<String>();



		try {
			Envelope envelope = new Envelope();
			while( iterator.hasNext() ) {

				try {

					ShapeFeature feature = new ShapeFeature();

					SimpleFeature sFeature = iterator.next();

					feature.id = (String)sFeature.getID();
			    	feature.geom = JTS.transform((Geometry)sFeature.getDefaultGeometry(),  transform);

			    	envelope.expandToInclude(feature.geom.getEnvelopeInternal());
			    	
			    	this.type = feature.geom.getGeometryType().toLowerCase();
			    	
			        for(Object attr : sFeature.getProperties()) {
			        	if(attr instanceof Property) {
			        		Property p = ((Property)attr);
			        		String name = Attribute.convertNameToId(p.getName().toString());
			        		PropertyType pt = p.getType();
			        		Object value = p.getValue();
			        		
			        		updateAttributeStats(name, value);
			        		
			        		if(value != null && (value instanceof Long)) {
			        			feature.attributes.put(name, (int)(long)p.getValue());

			        			fieldnamesFound.add(name);

			        		} else if( value instanceof Integer) {
			        			feature.attributes.put(name, (int)p.getValue());

			        			fieldnamesFound.add(name);
			        		}
			        		else if(value != null && (value instanceof Double )) {
			        			feature.attributes.put(name, (int)(long)Math.round((Double)p.getValue()));

			        			fieldnamesFound.add(name);

			        		}
			        	}
			        }
			    	features.add(new Fun.Tuple2<String,ShapeFeature>(feature.id, feature));
				}
				catch(Exception e) {
					skippedFeatures++;
					e.printStackTrace();
					continue;
				}
		     }
			
			this.bounds = new Bounds(envelope);
		}
		finally {
		     iterator.close();
		}

		dataStore.dispose();

		cleanupUnzippedShapefile();

		return features;
	}

	@JsonIgnore
	public File getUnzippedShapefile() throws ZipException, IOException {
		// unpack zip into temporary directory and return handle to *.shp file

		File outputDirectory = getTempShapeDirPath();

		ZipFile zipFile = new ZipFile(this.file);

		Enumeration<? extends ZipEntry> entries = zipFile.entries();

        while (entries.hasMoreElements()) {

            ZipEntry entry = entries.nextElement();
            File entryDestination = new File(outputDirectory,  entry.getName());

            entryDestination.getParentFile().mkdirs();

            if (entry.isDirectory())
                entryDestination.mkdirs();
            else {
                InputStream in = zipFile.getInputStream(entry);
                OutputStream out = new FileOutputStream(entryDestination);
                IOUtils.copy(in, out);
                IOUtils.closeQuietly(in);
                IOUtils.closeQuietly(out);
            }
        }

        for(File f : outputDirectory.listFiles()) {
        	if(f.getName().toLowerCase().endsWith(".shp"))
        		return f;
        }

        return null;
	}

	public void cleanupUnzippedShapefile() throws IOException {

		FileUtils.deleteDirectory(getTempShapeDirPath());

	}

	public void save() {

		// assign id at save
		if(id == null || id.isEmpty()) {
			Logger.info("created shapefile  " + id);
		}

		shapefilesData.save(id, this);

		Logger.info("saved shapefile " +id);
	}

	public void delete() {
		shapefilesData.delete(id);

		if(file != null && file.exists())
			file.delete();

		try {
			cleanupUnzippedShapefile();
		} catch (IOException e) {
			Logger.error("unable delete shapefile p " +id);
			e.printStackTrace();
		}

		Logger.info("delete shapefile p " +id);
	}

	static public Shapefile getShapefile(String id) {

		return shapefilesData.getById(id);
	}

	static public Collection<Shapefile> getShapfiles(String projectId) {
		if(projectId == null)
			return shapefilesData.getAll();

		else {

			Collection<Shapefile> data = new ArrayList<Shapefile>();

			for(Shapefile sf : shapefilesData.getAll()) {
				if(sf.projectId == null )
					sf.delete();
				else if(sf.projectId.equals(projectId))
					data.add(sf);
			}

			return data;
		}

	}

	public static void writeAllToClusterCache() {
		ExecutionContext ctx = Akka.system().dispatchers().defaultGlobalDispatcher();
		
		for (final Shapefile shapefile : getShapfiles(null)) {
			Akka.system().scheduler().scheduleOnce(Duration.create(10, "milliseconds"), new Runnable() {
				
				@Override
				public void run() {
					try {
						shapefile.writeToClusterCache();
					} catch (Exception e) {
						Logger.error("Exception writing " + shapefile + " to cluster cache: " + e);
					}
				}
			}, ctx);
		}
	}
}
