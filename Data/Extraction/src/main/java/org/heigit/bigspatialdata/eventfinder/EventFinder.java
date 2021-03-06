package org.heigit.bigspatialdata.eventfinder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.math3.exception.ConvergenceException;
import org.apache.commons.math3.exception.TooManyIterationsException;
import org.apache.commons.math3.fitting.WeightedObservedPoint;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDBDatabase;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDBH2;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDBIgnite;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDBJdbc;
import org.heigit.bigspatialdata.oshdb.api.generic.OSHDBCombinedIndex;
import org.heigit.bigspatialdata.oshdb.api.mapreducer.OSMContributionView;
import org.heigit.bigspatialdata.oshdb.api.object.OSMContribution;
import org.heigit.bigspatialdata.oshdb.osm.OSMType;
import org.heigit.bigspatialdata.oshdb.util.OSHDBBoundingBox;
import org.heigit.bigspatialdata.oshdb.util.OSHDBTimestamp;
import org.heigit.bigspatialdata.oshdb.util.celliterator.ContributionType;
import org.heigit.bigspatialdata.oshdb.util.time.OSHDBTimestamps;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Polygonal;
import org.slf4j.LoggerFactory;
import org.wololo.geojson.Feature;
import org.wololo.geojson.FeatureCollection;
import org.wololo.geojson.GeoJSONFactory;
import org.wololo.jts2geojson.GeoJSONReader;

public class EventFinder {

  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(EventFinder.class);

  public static void main(String[] args) throws Exception {

    LOG.info("Start preparation");

    String rootPath = Thread.currentThread().getContextClassLoader().getResource("").getPath();
    String propertiesPath = rootPath + "oshdb.properties";

    Properties oshdbProperties = new Properties();
    oshdbProperties.load(new FileInputStream(propertiesPath));

    OSHDBDatabase oshdb;
    OSHDBJdbc keytables;
    if (oshdbProperties.getProperty("type").contains("H2")) {
      oshdb = (new OSHDBH2(oshdbProperties.getProperty("oshdb")))
          .multithreading(true)
          .inMemory(false);
      keytables = (OSHDBJdbc) oshdb;
    } else {
      oshdb = new OSHDBIgnite(EventFinder.class.getResource("/ignite-dev-ohsome-client.xml")
          .getFile());
      oshdb.prefix("global_v5");
      Connection conn = DriverManager.getConnection(
          "jdbc:postgresql://10.11.12.21:5432/keytables-global_b", "ohsome", args[0]);
      keytables = new OSHDBJdbc(conn);
    }
    
    String months_file = oshdbProperties.getProperty("months_file");
    
    Boolean produce = Boolean.valueOf(oshdbProperties.getProperty("produce"));

    String[] split = oshdbProperties.getProperty("bbox").split(",");

    OSHDBBoundingBox bb = new OSHDBBoundingBox(
        Double.valueOf(split[0]),
        Double.valueOf(split[1]),
        Double.valueOf(split[2]),
        Double.valueOf(split[3]));

    Map<Integer, Polygon> polygons = EventFinder.getPolygons();

    SortedMap<OSHDBCombinedIndex<Integer, OSHDBTimestamp>, MappingMonth> queryDatabase
        = new TreeMap<OSHDBCombinedIndex<Integer, OSHDBTimestamp>, MappingMonth>();
    
    String end_date = oshdbProperties.getProperty("end_date");
    
    if (!produce) {
    	System.out.println("reading months file");
    	QueryOutput qOutput = FileActions.read_csv(months_file);
    	queryDatabase = qOutput.get_results();
    	String start_date = qOutput.get_end_month();
    	SortedMap<OSHDBCombinedIndex<Integer, OSHDBTimestamp>, MappingMonth> queryDatabase2 = 
    	    EventFinder.queryDatabase(bb, oshdb, keytables, polygons, start_date, end_date);
    	for (OSHDBCombinedIndex<Integer, OSHDBTimestamp> m: queryDatabase2.keySet()) {
    		queryDatabase.put(m, queryDatabase2.get(m));
    	}
    	FileActions.append_csv(months_file, queryDatabase2, end_date);
    } else {
    	queryDatabase = EventFinder.queryDatabase(bb, oshdb, keytables, polygons, "2004-01-01", end_date);
    	FileActions.write_csv(months_file, queryDatabase, end_date);
    }

    Map<Integer, ArrayList<MappingEvent>> events = EventFinder
        .extractEvents(queryDatabase, oshdb, keytables, polygons, bb);
    
    String follow_up_file = oshdbProperties.getProperty("follow_up_file");
    FileWriter file = new FileWriter(follow_up_file);
    file.write("GeomID,Timestamp,edited_entities\n");
    file.close();
    
    Map<OSHDBTimestamp, Map<Integer, MappingEvent>> enhanceResult = EventFinder
        .enhanceResult(oshdb,
            keytables,
            polygons,
            bb,
            events,
            follow_up_file);
    
    oshdb.close();

    EventFinder.writeOutput(enhanceResult);

  }

  public static SortedMap<OSHDBCombinedIndex<Integer, OSHDBTimestamp>, MappingMonth> queryDatabase(
      OSHDBBoundingBox bb,
      OSHDBDatabase oshdb,
      OSHDBJdbc keytables,
      Map<Integer, Polygon> polygons,
      String startMonth,
      String endMonth)
      throws IOException, Exception {

    LOG.info("Run Query");

    StopWatch createStarted = StopWatch.createStarted();
    // collect contributions by month
    SortedMap<OSHDBCombinedIndex<Integer, OSHDBTimestamp>, MappingMonth> result = OSMContributionView
        .on(oshdb)
        .keytables(keytables)
        .areaOfInterest(bb)
        //Relations are excluded because they hold only little extra information and make this process very slow!
        .osmType(OSMType.NODE, OSMType.WAY)
        .timestamps(startMonth, endMonth, OSHDBTimestamps.Interval.MONTHLY)
        .aggregateByGeometry(polygons)
        .aggregateByTimestamp(OSMContribution::getTimestamp)
        .map(new MapFunk())
        .reduce(new NewMapMonth(), new MonthCombiner());

    createStarted.stop();
    double toMinutes = (createStarted.getTime() / 1000.0) / 60.0;

    LOG.info("Query Finished, took " + toMinutes + " minutes");
    return result;
  }

  /*
 * This procedure identifies large scale events within osm data using the oshdb api
 * I define here events as large contributions in relation to the current development of the data base (i.e. relatively).
 * The procedures assumes that the accumulative number contribution actions (meaning the individual actions that make a contribution, e.g. deleting or adding a coordinate or a tag) follows 
 * an s-shaped (logistic) curve over time.
 * Accordingly, the procedure counts the accumulative number of actions for each month and fits a logistic curve of the type: a/(1+b*exp(-k*(t-u))), where t is a temporal index, with the data.
 * Differences between observed values and estimations ('errors') are calculated. To eliminate temporal dependency, the procedure uses lagged errors (lagged error at time t=
 * error at time t - error at time t-1).
 * The procedure normalizes the lagged errors and identifies significantly positive values at 95% confidence level as events.
 * For each event, the procedure records information regarding its date, number of active users, number of actions, maximal number of actions by a single user, relative change in the database size, 
 * and number of contributions by type.
   */
  public static Map<Integer, ArrayList<MappingEvent>> extractEvents(
      SortedMap<OSHDBCombinedIndex<Integer, OSHDBTimestamp>, MappingMonth> months,
      OSHDBDatabase oshdb,
      OSHDBJdbc keytables,
      Map<Integer, Polygon> polygons,
      OSHDBBoundingBox bb)
      throws Exception {

    LOG.info("Start curve-fitting");
    StopWatch createStarted = StopWatch.createStarted();
    // saves objects of type Mapping_Event which stores the month of the event, the number of active mappers, number of contributions, and maximal number of contributions by one user
    final Map<Integer, ArrayList<MappingEvent>> out = new HashMap<>();

    //devide result into resulty per geometry
    SortedMap<Integer, SortedMap<OSHDBTimestamp, MappingMonth>> nest
        = OSHDBCombinedIndex.nest(months);

    File conv_file = new File("target/Convergence_errors.csv");
    FileWriter conv_writer = new FileWriter(conv_file);
    conv_writer.write("Reason;GeomNr.\n");
    File time_file = new File("target/convTime.csv");
    FileWriter time_writer = new FileWriter(time_file);
    time_writer.write("GeomNr.;Time\n");

    //iterate
    nest.forEach((Integer geom, SortedMap<OSHDBTimestamp, MappingMonth> geomContributions) -> {
      ArrayList<MappingEvent> list = new ArrayList<>();

      // remove entries before first contribution
      while (geomContributions.get(geomContributions.firstKey())
          .get_contributions() == 0) {
        geomContributions.remove(geomContributions.firstKey());
        if (geomContributions.isEmpty()) {
          return;
        }
      }

      // remove entries after last contribution
      while (geomContributions.get(geomContributions.lastKey()).get_contributions() == 0) {
        geomContributions.remove(geomContributions.lastKey());
      }

      // create accumulative data
      SortedMap<OSHDBTimestamp, Integer> acc_result = new TreeMap<>();
      Integer conts = 0;
      for (Entry<OSHDBTimestamp, MappingMonth> entry : geomContributions.entrySet()) {
        conts += entry.getValue().get_contributions();
        acc_result.put(entry.getKey(), conts);
      }

      // create data for curve fitting
      SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
      Date date2007 = null;
      try {
        date2007 = dateFormat.parse("2007-09-30");
      } catch (ParseException e) {
        LOG.error("", e);
      }
      ArrayList<WeightedObservedPoint> points = new ArrayList<>();
      int i = 0;
      int start_time = 0;
      Iterator<OSHDBTimestamp> values = acc_result.keySet().iterator();
      while (values.hasNext()) {
        OSHDBTimestamp d = values.next();
        float v = acc_result.get(d);
        Date date = d.toDate();
        Boolean aft = date.after(date2007);
        if (aft) {
          WeightedObservedPoint point = new WeightedObservedPoint(1.0, i, v);
          points.add(point);
        } else {
          start_time++;
        }
        i++;
      }

      StopWatch fitting = StopWatch.createStarted();
      // fit curve
      MyFuncFitter fitter = new MyFuncFitter();
      double[] coeffs = null;

      try {
        coeffs = fitter.fit(points);
      } catch (ConvergenceException ex) {
        try {
          conv_writer.write("ConvergenceError;" + geom.toString() + "\n");
        } catch (IOException ex1) {
          LOG.error("", ex1);
        }
        LOG.warn("Geom " + geom + " did not converge!", ex);
        return;
      } catch (TooManyIterationsException e) {
        try {
          conv_writer.write("TooManyIterations;" + geom.toString() + "\n");
        } catch (IOException ex1) {
          LOG.error("", ex1);
        }
        LOG.warn("Geom " + geom + " would have needed more then 10'000 Iterations!", e);
        return;

      } finally {
        fitting.stop();
        try {
          time_writer.write(geom + ";" + (fitting.getTime() / 1000.0) + "\n");
          time_writer.flush();
        } catch (IOException ex) {
          LOG.error("could not write to file", ex);
        }
      }

      // compute errors
      HashMap<OSHDBTimestamp, Double> errors = new HashMap<>();
      for (Entry<OSHDBTimestamp, MappingMonth> entry : geomContributions.entrySet()) {
        Double value = coeffs[0] / (1.0 + coeffs[1] * Math.exp(-coeffs[2] * (i - coeffs[3])));
        errors.put(entry.getKey(), acc_result.get(entry.getKey()) - value);
      }

      // get lagged errors
      HashMap<OSHDBTimestamp, Double> lagged_errors = new HashMap<>();
      for (i = 1; i < geomContributions.keySet().size(); i++) {
        Double value = errors.get(geomContributions.keySet().toArray()[i])
            - errors.get(geomContributions.keySet().toArray()[i - 1]);
        lagged_errors.put((OSHDBTimestamp) geomContributions.keySet().toArray()[i], value);
      }

      // compute mean and standard deviation for lagged errors
      Double mean = 0.;
      for (Double err : lagged_errors.values()) {
        mean += err;
      }
      mean /= lagged_errors.size();
      double std = 0.;
      for (double num : lagged_errors.values()) {
        std += Math.pow(num - mean, 2);
      }
      std = Math.sqrt(std / (lagged_errors.size() - 1.));

      Iterator<Entry<OSHDBTimestamp, MappingMonth>> iterator1 = geomContributions.entrySet()
          .iterator();
      iterator1.next();

      i = 1;
      while (iterator1.hasNext()) {
        Entry<OSHDBTimestamp, MappingMonth> next = iterator1.next();
        // identify events
        OSHDBTimestamp m_lag = (OSHDBTimestamp) geomContributions.keySet().toArray()[i - 1];
        Double error = (lagged_errors.get(next.getKey()) - mean) / std; // normalized error
        if (error > 1.644854) { // if error is positively significant at 95% - create event
          int edited_entities = 0;
          if (next.getValue().get_users_number()==0) {
        	  next.getValue().set_users_number(next.getValue().getUser_counts().size());
          }
          if (next.getValue().get_max_cont()==0) {
        	  next.getValue().set_max_cont(Collections.max(next.getValue().getUser_counts().values()));
          }
          MappingEvent e = new MappingEvent(
              next.getKey(),
              next.getValue(),
              next.getValue().get_users_number(),
              acc_result.get(next.getKey()) - acc_result.get(m_lag),
              ((acc_result.get(next.getKey()) - (float) acc_result.get(m_lag))
              / acc_result.get(m_lag)),
              next.getValue().get_max_cont(),
              coeffs,
              next.getValue().get_type_counts(),
              edited_entities,
              error,
              start_time + i);
          list.add(e);
        }
        i++;
      }
      out.put(geom, list); // add to list of events
    });

    conv_writer.close();

    createStarted.stop();

    double toMinutes = (createStarted.getTime() / 1000.0) / 60.0;

    LOG.info("Curve-fitting finished, took " + toMinutes + " minutes");
    return out;
  }

  private static Map<OSHDBTimestamp, Map<Integer, MappingEvent>> enhanceResult(
      OSHDBDatabase oshdb,
      OSHDBJdbc keytables,
      Map<Integer, Polygon> polygons,
      OSHDBBoundingBox bb,
      Map<Integer, ArrayList<MappingEvent>> events,
      String filename) {
    LOG.info("Start querying entities changed");
    StopWatch createStarted = StopWatch.createStarted();

    Map<OSHDBTimestamp, Map<Integer, MappingEvent>> loop = new TreeMap<>();
    Map<OSHDBTimestamp, Map<Integer, MappingEvent>> result = new TreeMap<>();

    //group by month
    events.forEach((geom, eventies) -> {
      eventies.forEach(event -> {
        Map<Integer, MappingEvent> input
            = loop.getOrDefault(event.getTimestap(), new TreeMap<>());
        input.put(geom, event);
        loop.put(event.getTimestap(), input);
      });
    });

    int size = loop.size();
    int[] i = new int[]{1};
    loop.forEach((ts, map) -> {
      Map<Integer, Integer> edited_entities = new HashMap<>();
      Map<Integer, Polygon> geoms = new HashMap<>();
      Date date = ts.toDate();
      date.setMonth(date.getMonth() + 1);
      TimeZone tz = TimeZone.getTimeZone("UTC");
      DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"); // Quoted "Z" to indicate UTC, no timezone offset
      df.setTimeZone(tz);
      String nowAsISO = df.format(date);
      OSHDBTimestamps tss = new OSHDBTimestamps(
          ts.toString(),
          nowAsISO);
      polygons.forEach((id, poly) -> {
        if (map.containsKey(id)) {
          geoms.put(id, poly);
        }
      });
      try {
        edited_entities = EventFinder.queryEntityEdits(
            oshdb,
            keytables,
            geoms,
            bb,
            tss);
      } catch (Exception ex) {
        LOG.error("", ex);
      }
      
      try {
        final BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true));
        HashMap<Integer, MappingEvent> output = new HashMap<>();
        edited_entities.forEach((geom, num) -> {
            MappingEvent get1 = map.get(geom);
            get1.setEntitiesChanged(num);
            output.put(geom, get1);
            try {
				writer.write(geom+","+ts+","+num+"\n");
			} catch (IOException e) {
				e.printStackTrace();
			}
        });
        result.put(ts, output);
        writer.close();
      } catch (IOException e) {
		e.printStackTrace();
	  }
      LOG.info(i[0] + "/" + size + ": finished timestamp " + ts.toString());
      i[0] += 1;
    });
    createStarted.stop();
    double toMinutes = (createStarted.getTime() / 1000.0) / 60.0;

    LOG.info("Querying entities changed finished, took " + toMinutes + " minutes");
    return result;
  }

  private static Map<Integer, Polygon> getPolygons() throws IOException {
    LOG.info("Read Polygons");
    //read geometries
    ClassLoader classloader = Thread.currentThread().getContextClassLoader();
    InputStream is = classloader.getResourceAsStream("grid_20000_id.geojson");

    BufferedReader br = new BufferedReader(new InputStreamReader(is));
    StringBuilder sb = new StringBuilder();
    br.lines().forEach(line -> sb.append(line));
    String geoJson = sb.toString();

    Map<Integer, Polygon> geometries = new HashMap<>();
    FeatureCollection featureCollection = (FeatureCollection) GeoJSONFactory.create(geoJson);
    GeometryFactory gf = new GeometryFactory();
    GeoJSONReader gjr = new GeoJSONReader();

    for (Feature f : featureCollection.getFeatures()) {
      geometries.put((Integer) f.getProperties().get("id"),
          gf.createPolygon(gjr.read(f.getGeometry()).getCoordinates()));
    }
    return geometries;
  }

  public static void writeOutput(Map<OSHDBTimestamp, Map<Integer, MappingEvent>> events)
      throws IOException {
    LOG.info("Save output");

    //write output
    File file = new File("target/MappingEvents.csv");

    //Create the file
    file.createNewFile();

    try ( //Write Content
        FileWriter writer = new FileWriter(file)) {
      writer.write(
          "ID;GeomNr.;EventNr.;Timestamp;StartTime;Users;Contributions;Change;MaxContributions;EditedEntitities;AverageGeomChanges;AverageTagChanges;Pvalue;Coeffs;TypeCounts\n");

      int[] id = {0};
      String pattern = "yyyy-MM";
      DateFormat df = new SimpleDateFormat(pattern);
      events.forEach((ts, eventMap) -> {
        if (eventMap.isEmpty()) {
          return;
        }

        int[] eventnr = {1};
        eventMap.forEach((geom, e) -> {
          try {
            writer.write(
                id[0] + ";"
                + geom + ";"
                + eventnr[0] + ";"
                + df.format(e.getTimestap().toDate()) + ";"
                + e.get_start_time() + ";"
                + e.getUsers() + ";"
                + e.get_contributions() + ";"
                + e.getDeltakontrib() + ";"
                + e.getMaxCont() + ";"
                + e.getEntitiesChanged() + ";"
                + e.get_geom_change_average() + ";"
                + e.get_tag_change_average() + ";"
                + e.get_pvalue() + ";"
                + Arrays.toString(e.getCoeffs()) + ";"
                + e.get_type_counts().toString() + ";"
                + "\n"
            );
          } catch (IOException ex) {
            LOG.error("Could not write output.", ex);
          }
          eventnr[0] += 1;
          id[0] += 1;
        });
      });
    }

    LOG.info("Finished");

  }

  private static Map<Integer, Integer> queryEntityEdits(
      OSHDBDatabase oshdb,
      OSHDBJdbc keytables,
      Map<Integer, Polygon> geoms,
      OSHDBBoundingBox bb,
      OSHDBTimestamps ts) throws Exception {
    GeometryFactory geometryFactory = new GeometryFactory();
    Geometry aoi = geometryFactory.createPolygon();
    Iterator<Entry<Integer, Polygon>> iterator = geoms.entrySet().iterator();
    while (iterator.hasNext()) {
      Entry<Integer, Polygon> next = iterator.next();
      aoi = aoi.union(next.getValue());
    }

    // collect contributions by month
    Map<Integer, Integer> result = OSMContributionView
        .on(oshdb)
        .keytables(keytables)
        .areaOfInterest(bb)
        .areaOfInterest((Geometry & Polygonal) aoi)
        //Relations are excluded because they hold only little extra information and make this process very slow!
        .osmType(OSMType.NODE, OSMType.WAY)
        .timestamps(ts)
        .groupByEntity()
        .map(list -> {
          Map<Integer, Integer> map = new HashMap<>(1);
          list.forEach(contrib -> {
            Geometry geometry;
            if (contrib.getContributionTypes().contains(ContributionType.DELETION)) {
              geometry = contrib.getGeometryBefore();
            } else {
              geometry = contrib.getGeometryAfter();
            }
            geoms.forEach((id, geomb) -> {
              if (geomb.intersects(geometry)) {
                map.put(id, 1);
              }
            });
          });
          return map;
        })
        .reduce(
            () -> new HashMap<Integer, Integer>(),
            (Map<Integer, Integer> map1, Map<Integer, Integer> map2) -> {
          HashMap<Integer, Integer> hashMap = new HashMap<>();
          hashMap.putAll(map1);
          map2.forEach((geo, num) -> {
            hashMap.merge(geo, num, (a, b) -> a + b);
          });
          return hashMap;
        });

    return result;

  }

}
