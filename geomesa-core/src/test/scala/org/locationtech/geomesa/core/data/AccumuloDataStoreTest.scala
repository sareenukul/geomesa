/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.core.data

import java.text.SimpleDateFormat
import java.util.Date

import com.vividsolutions.jts.geom.Coordinate
import org.apache.accumulo.core.client.mock.MockInstance
import org.apache.accumulo.core.client.security.tokens.PasswordToken
import org.apache.accumulo.core.client.{BatchWriterConfig, IteratorSetting}
import org.apache.accumulo.core.data.{Mutation, Range}
import org.apache.accumulo.core.iterators.user.VersioningIterator
import org.apache.accumulo.core.security.Authorizations
import org.apache.commons.codec.binary.Hex
import org.apache.hadoop.io.Text
import org.geotools.data._
import org.geotools.data.collection.ListFeatureCollection
import org.geotools.data.simple.SimpleFeatureStore
import org.geotools.factory.{CommonFactoryFinder, Hints}
import org.geotools.feature.simple.SimpleFeatureBuilder
import org.geotools.feature.{DefaultFeatureCollection, NameImpl}
import org.geotools.filter.text.cql2.CQL
import org.geotools.filter.text.ecql.ECQL
import org.geotools.geometry.jts.JTSFactoryFinder
import org.geotools.process.vector.TransformProcess
import org.geotools.referencing.CRS
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.locationtech.geomesa.core.index._
import org.locationtech.geomesa.core.iterators.{IndexIterator, TestData}
import org.locationtech.geomesa.core.security.{AuthorizationsProvider, DefaultAuthorizationsProvider, FilteringAuthorizationsProvider}
import org.locationtech.geomesa.core.util.{CloseableIterator, SelfClosingIterator}
import org.locationtech.geomesa.feature.AvroSimpleFeatureFactory
import org.locationtech.geomesa.utils.geotools.Conversions._
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes._
import org.locationtech.geomesa.utils.text.WKTUtils
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter
import org.opengis.filter.sort.{SortBy, SortOrder}
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.JavaConversions._
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class AccumuloDataStoreTest extends Specification {

  sequential

  val ff = CommonFactoryFinder.getFilterFactory2
  val geotimeAttributes = org.locationtech.geomesa.core.index.spec
  var id = 0
  val hints = new Hints(Hints.FEATURE_FACTORY, classOf[AvroSimpleFeatureFactory])
  val featureFactory = CommonFactoryFinder.getFeatureFactory(hints)
  val WGS84 = DefaultGeographicCRS.WGS84
  val gf = JTSFactoryFinder.getGeometryFactory

  "AccumuloDataStore" should {
    "create a data store" >> {
      val ds = createStore
      "that is not null" >> { ds must not be null }
      "and create a schema" >> {
        val sftName = "testType"
        val sft = SimpleFeatureTypes.createType(sftName, s"NAME:String,$geotimeAttributes")
        sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
        ds.createSchema(sft)

        "getSchema must return null when name exists" >> {
          ds.getSchema(sftName) must not be null
        }

        "getTypeNames should contain newly created type" >> { ds.getTypeNames.toSeq must contain("testType") }

        "provide ability to write using the feature source and read what it wrote" >> {
          val fs = ds.getFeatureSource(sft.getTypeName).asInstanceOf[FeatureStore[SimpleFeatureType, SimpleFeature]]

          // create a feature
          val builder = new SimpleFeatureBuilder(sft, featureFactory)
          val liveFeature = builder.buildFeature("fid-1")
          val geom = WKTUtils.read("POINT(45.0 49.0)")
          liveFeature.setDefaultGeometry(geom)

          // make sure we ask the system to re-use the provided feature-ID
          liveFeature.getUserData()(Hints.USE_PROVIDED_FID) = java.lang.Boolean.TRUE

          val featureCollection = new DefaultFeatureCollection(sft.getTypeName, sft)

          featureCollection.add(liveFeature)

          // write the feature to the store
          val res = fs.addFeatures(featureCollection)

          // compose a CQL query that uses a reasonably-sized polygon for searching
          val cqlFilter = CQL.toFilter(s"BBOX(geom, 44.9,48.9,45.1,49.1)")
          val query = new Query(sftName, cqlFilter)

          // Let's read out what we wrote.
          val results = fs.getFeatures(query)
          val features = results.features
          var containsGeometry = false

          while (features.hasNext) {
            containsGeometry = containsGeometry | features.next.getDefaultGeometry.equals(geom)
          }

          "results schema should match" >> { results.getSchema should be equalTo sft }
          "geometry should be set" >> { containsGeometry should be equalTo true }
          "result length should be 1" >> { res.length should be equalTo 1 }
        }

        "return NULL when a feature name does not exist" in {
          val sftName = "testTypeThatDoesNotExist"
          ds.getSchema(sftName) must beNull
        }

        "return an empty iterator correctly" in {
          // create the data store
          val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]

          // create a feature
          val geom = WKTUtils.read("POINT(45.0 49.0)")
          val builder = new SimpleFeatureBuilder(sft, featureFactory)
          builder.addAll(List("testType", geom, null))
          val liveFeature = builder.buildFeature("fid-1")

          // make sure we ask the system to re-use the provided feature-ID
          liveFeature.getUserData()(Hints.USE_PROVIDED_FID) = java.lang.Boolean.TRUE

          val featureCollection = new DefaultFeatureCollection(sftName, sft)

          featureCollection.add(liveFeature)

          // write the feature to the store
          val res = fs.addFeatures(featureCollection)
          "after writing 1 feature" >> { res.length should be equalTo 1 }

          // compose a CQL query that uses a polygon that is disjoint with the feature bounds
          val cqlFilter = CQL.toFilter(s"BBOX(geom, 64.9,68.9,65.1,69.1)")
          val query = new Query(sftName, cqlFilter)

          // Let's read out what we wrote.
          val results = fs.getFeatures(query)
          val features = results.features

          "where schema matches" >> { results.getSchema should be equalTo sft }
          "and there are no results" >> { features.hasNext should be equalTo false }
        }
      }

      "create a schema with custom record splitting options" >> {
        val spec = "name:String,dtg:Date,*geom:Point:srid=4326;table.splitter.class=org.locationtech.geomesa.core.data.DigitSplitter,table.splitter.options=fmt:%02d,min:0,max:99"
        val sft = SimpleFeatureTypes.createType("customsplit", spec)
        org.locationtech.geomesa.core.index.setTableSharing(sft, false)
        ds.createSchema(sft)
        val recTable = ds.getRecordTableForType(sft)
        val splits = ds.connector.tableOperations().listSplits(recTable)
        splits.size() must be equalTo 100
        splits.head must be equalTo new Text("00")
        splits.last must be equalTo new Text("99")
      }

      "process a DWithin query correctly" in {
        // create the data store
        val sftName = "dwithintest"
        val sft = SimpleFeatureTypes.createType(sftName, s"NAME:String,dtg:Date,*geom:Point:srid=4326")
        sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
        ds.createSchema(sft)

        val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]

        // create a feature
        val geom = WKTUtils.read("POINT(45.0 49.0)")
        val builder = new SimpleFeatureBuilder(sft, featureFactory)
        builder.addAll(List("testType", null, geom))
        val liveFeature = builder.buildFeature("fid-1")

        // make sure we ask the system to re-use the provided feature-ID
        liveFeature.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
        val featureCollection = new DefaultFeatureCollection(sftName, sft)
        featureCollection.add(liveFeature)
        fs.addFeatures(featureCollection)

        // compose a CQL query that uses a polygon that is disjoint with the feature bounds
        val geomFactory = JTSFactoryFinder.getGeometryFactory
        val q = ff.dwithin(ff.property("geom"), ff.literal(geomFactory.createPoint(new Coordinate(45.000001, 48.99999))), 100.0, "meters")
        val query = new Query(sftName, q)

        // Let's read out what we wrote.
        val results = fs.getFeatures(query)
        val features = results.features
        val f = features.next()

        "with correct result" >> { f.getID mustEqual "fid-1" }
        "and no more results" >> { features.hasNext must beFalse }
      }

      "process an OR query correctly" in {
        val sftName = "ortest"
        val sft = SimpleFeatureTypes.createType(sftName, s"NAME:String,dtg:Date,*geom:Point:srid=4326")
        sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
        ds.createSchema(sft)

        val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]

        {
          val randVal: (Double, Double) => Double = {
            val r = new Random(System.nanoTime())
            (low, high) => {
              (r.nextDouble() * (high - low)) + low
            }
          }
          val fc = new DefaultFeatureCollection(sftName, sft)
          for (i <- 0 until 1000) {
            val lat = randVal(-0.001, 0.001)
            val lon = randVal(-0.001, 0.001)
            val geom = WKTUtils.read(s"POINT($lat $lon)")
            val builder = new SimpleFeatureBuilder(sft, featureFactory)
            builder.addAll(List("testType", null, geom))
            val feature = builder.buildFeature(s"fid-$i")
            feature.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
            fc.add(feature)
          }
          fs.addFeatures(fc)
        }

        val geomFactory = JTSFactoryFinder.getGeometryFactory
        val urq = ff.dwithin(ff.property("geom"), ff.literal(geomFactory.createPoint(new Coordinate( 0.0005,  0.0005))), 150.0, "meters")
        val llq = ff.dwithin(ff.property("geom"), ff.literal(geomFactory.createPoint(new Coordinate(-0.0005, -0.0005))), 150.0, "meters")
        val orq = ff.or(urq, llq)
        val andq = ff.and(urq, llq)
        val urQuery  = new Query(sftName,  urq)
        val llQuery  = new Query(sftName,  llq)
        val orQuery  = new Query(sftName,  orq)
        val andQuery = new Query(sftName, andq)

        val urNum  = fs.getFeatures( urQuery).features.length
        val llNum  = fs.getFeatures( llQuery).features.length
        val orNum  = fs.getFeatures( orQuery).features.length
        val andNum = fs.getFeatures(andQuery).features.length

        "obeying inclusion-exclusion principle" >> {
          (urNum + llNum) mustEqual (orNum + andNum)
        }
      }

      "handle transformations" in {
        val sftName = "transformtest1"
        val sft = SimpleFeatureTypes.createType(sftName, s"name:String,dtg:Date,*geom:Point:srid=4326")
        sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
        ds.createSchema(sft)

        val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]

        // create a feature
        val geom = WKTUtils.read("POINT(45.0 49.0)")
        val builder = new SimpleFeatureBuilder(sft, featureFactory)
        builder.addAll(List("testType", null, geom))
        val liveFeature = builder.buildFeature("fid-1")

        // make sure we ask the system to re-use the provided feature-ID
        liveFeature.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
        val featureCollection = new DefaultFeatureCollection(sftName, sft)
        featureCollection.add(liveFeature)
        fs.addFeatures(featureCollection)

        val query = new Query(sftName, Filter.INCLUDE,
          Array("name", "derived=strConcat('hello',name)", "geom"))

        // Let's read out what we wrote.
        val results = fs.getFeatures(query)
        val features = results.features
        val f = features.next()

        "with matching schema" >> {
          s"name:String,*geom:Point:srid=4326:index=true:$OPT_INDEX_VALUE=true,derived:String" mustEqual
            SimpleFeatureTypes.encodeType(results.getSchema)
        }

        "and correct result" >> { "fid-1=testType|POINT (45 49)|hellotestType" mustEqual DataUtilities.encodeFeature(f) }
      }

      "handle transformations with dtg and geom" in {
        val sftName = "transformtest5"
        val sft = SimpleFeatureTypes.createType(sftName, s"name:String,dtg:Date,*geom:Point:srid=4326")
        sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
        ds.createSchema(sft)

        val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]

        // create a feature
        val geom = WKTUtils.read("POINT(45.0 49.0)")
        val dtg = new Date(0)
        val builder = new SimpleFeatureBuilder(sft, featureFactory)
        builder.addAll(List("testType", dtg, geom))
        val liveFeature = builder.buildFeature("fid-1")

        // make sure we ask the system to re-use the provided feature-ID
        liveFeature.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
        val featureCollection = new DefaultFeatureCollection(sftName, sft)
        featureCollection.add(liveFeature)
        fs.addFeatures(featureCollection)

        val query = new Query(sftName, Filter.INCLUDE, List("dtg", "geom").toArray)
        val results = SelfClosingIterator(CloseableIterator(ds.getFeatureSource(sftName).getFeatures(query).features())).toList
        results must haveSize(1)
        results(0).getAttribute("dtg") mustEqual(dtg)
        results(0).getAttribute("geom") mustEqual(geom)
        results(0).getAttribute("name") must beNull
      }

      "handle setPropertyNames transformations" in {
        val sftName = "transformtest7"
        val sft = SimpleFeatureTypes.createType(sftName, s"name:String,dtg:Date,*geom:Point:srid=4326")
        sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
        ds.createSchema(sft)

        val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]

        // create a feature
        val geom = WKTUtils.read("POINT(45.0 49.0)")
        val builder = new SimpleFeatureBuilder(sft, featureFactory)
        builder.addAll(List("testType", new Date, geom))
        val liveFeature = builder.buildFeature("fid-1")

        // make sure we ask the system to re-use the provided feature-ID
        liveFeature.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
        val featureCollection = new DefaultFeatureCollection(sftName, sft)
        featureCollection.add(liveFeature)

        fs.addFeatures(featureCollection)

        val filter = ff.bbox("geom", 44.0, 48.0, 46.0, 50.0, "EPSG:4326")
        val query = new Query(sftName, filter)
        query.setPropertyNames(Array("geom"))

        val features = fs.getFeatures(query).features

        val results = features.toList

        "must has exactly one result" >> { results.size  must equalTo(1) }
      }

      "handle transformations across multiple fields" in {
        // create the data store
        val sftName = "transformtest2"
        val sft = SimpleFeatureTypes.createType(sftName, s"name:String,attr:String,dtg:Date,*geom:Point:srid=4326")
        sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
        ds.createSchema(sft)

        val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]

        // create a feature
        val geom = WKTUtils.read("POINT(45.0 49.0)")
        val builder = new SimpleFeatureBuilder(sft, featureFactory)
        builder.addAll(List("testType", "v1", null, geom))
        val liveFeature = builder.buildFeature("fid-1")

        // make sure we ask the system to re-use the provided feature-ID
        liveFeature.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
        val featureCollection = new DefaultFeatureCollection(sftName, sft)
        featureCollection.add(liveFeature)
        fs.addFeatures(featureCollection)

        val query = new Query(sftName, Filter.INCLUDE,
          Array("name", "derived=strConcat(attr,name)", "geom"))

        // Let's read out what we wrote.
        val results = fs.getFeatures(query)
        val features = results.features
        val f = features.next()

        "with matching schemas" >> {
          s"name:String,*geom:Point:srid=4326:index=true:$OPT_INDEX_VALUE=true,derived:String" mustEqual SimpleFeatureTypes.encodeType(results.getSchema)
        }

        "and correct results" >> {
          "fid-1=testType|POINT (45 49)|v1testType" mustEqual DataUtilities.encodeFeature(f)
        }
      }

      "handle transformations to subtypes" in {
        // create the data store
        val sftName = "transformtest3"
        val sft = SimpleFeatureTypes.createType(sftName, s"name:String,attr:String,dtg:Date,*geom:Point:srid=4326")
        sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
        ds.createSchema(sft)

        val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]

        // create a feature
        val geom = WKTUtils.read("POINT(45.0 49.0)")
        val builder = new SimpleFeatureBuilder(sft, featureFactory)
        builder.addAll(List("testType", "v1", null, geom))
        val liveFeature = builder.buildFeature("fid-1")

        // make sure we ask the system to re-use the provided feature-ID
        liveFeature.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
        val featureCollection = new DefaultFeatureCollection(sftName, sft)
        featureCollection.add(liveFeature)
        fs.addFeatures(featureCollection)

        val query = new Query(sftName, Filter.INCLUDE, Array("name", "geom"))

        // Let's read out what we wrote.
        val results = fs.getFeatures(query)
        val features = results.features
        val f = features.next()

        "with matching schemas" >> {
          s"name:String,*geom:Point:srid=4326:index=true:$OPT_INDEX_VALUE=true" mustEqual SimpleFeatureTypes.encodeType(results.getSchema)
        }

        "and correct results" >> {
          "fid-1=testType|POINT (45 49)" mustEqual DataUtilities.encodeFeature(f)
        }
      }

      "handle transformations with filters on other attributes" in {
        // create the data store
        val sftName = "test4transform"
        val sft = SimpleFeatureTypes.createType(sftName, s"name:String,attr:String,dtg:Date,*geom:Point:srid=4326")
        sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
        ds.createSchema(sft)

        val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]

        // create a feature
        val geom = WKTUtils.read("POINT(50.0 49.0)")
        val builder = new SimpleFeatureBuilder(sft, featureFactory)
        builder.addAll(List("testType", "v1", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").parse("2014-01-01T12:30:00.000+0000"), geom))
        val liveFeature = builder.buildFeature("fid-1xxx")

        // make sure we ask the system to re-use the provided feature-ID
        liveFeature.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
        val featureCollection = new DefaultFeatureCollection(sftName, sft)
        featureCollection.add(liveFeature)
        fs.addFeatures(featureCollection)

        val query = new Query(sftName,
                              CQL.toFilter("bbox(geom,-180,-90,180,90) AND dtg BETWEEN '2013-01-01T00:00:00.000Z' AND '2015-01-02T00:00:00.000Z'"),
                              Array("geom"))

        // Let's read out what we wrote.
        val results = fs.getFeatures(query)
        val features = results.features
        "return the data" >> {
          features.hasNext must beTrue
        }
        "with correct results" >> {
          val f = features.next()
          DataUtilities.encodeFeature(f) mustEqual "fid-1xxx=POINT (50 49)"
        }
      }

      "handle requests with namespaces" in {
        // create the data store
        val ns = "mytestns"
        val sftName = "namespacetest"
        val sft = SimpleFeatureTypes.createType(sftName, s"name:String,dtg:Date,*geom:Point:srid=4326")
        sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
        ds.createSchema(sft)

        val schemaWithoutNs = ds.getSchema(sftName)

        schemaWithoutNs.getName.getNamespaceURI must beNull
        schemaWithoutNs.getName.getLocalPart mustEqual sftName

        val schemaWithNs = ds.getSchema(new NameImpl(ns, sftName))

        schemaWithNs.getName.getNamespaceURI mustEqual ns
        schemaWithNs.getName.getLocalPart mustEqual sftName
      }
    }

    "handle IDL correctly" in {
      val ds = createStore
      val sftName = TestData.featureName
      val sft = TestData.featureType
      sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
      ds.createSchema(sft)
      val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]
      val featureCollection = new DefaultFeatureCollection()
      featureCollection.addAll(TestData.allThePoints.map(TestData.createSF))
      fs.addFeatures(featureCollection)

      "default layer preview, bigger than earth, multiple IDL-wrapping geoserver BBOX" in {
        val spatial = ff.bbox("geom", -230, -110, 230, 110, CRS.toSRS(WGS84))
        val query = new Query(sftName, spatial)
        val results = fs.getFeatures(query)
        results.size() mustEqual 361
      }

      ">180 lon diff non-IDL-wrapping geoserver BBOX" in {
        val spatial = ff.bbox("geom", -100, 1.1, 100, 4.1, CRS.toSRS(WGS84))
        val query = new Query(sftName, spatial)
        val results = fs.getFeatures(query)
        results.size() mustEqual 6
      }

      "small IDL-wrapping geoserver BBOXes" in {
        val spatial1 = ff.bbox("geom", -181.1, -90, -175.1, 90, CRS.toSRS(WGS84))
        val spatial2 = ff.bbox("geom", 175.1, -90, 181.1, 90, CRS.toSRS(WGS84))
        val binarySpatial = ff.or(spatial1, spatial2)
        val query = new Query(sftName, binarySpatial)
        val results = fs.getFeatures(query)
        results.size() mustEqual 10
      }

      "large IDL-wrapping geoserver BBOXes" in {
        val spatial1 = ff.bbox("geom", -181.1, -90, 40.1, 90, CRS.toSRS(WGS84))
        val spatial2 = ff.bbox("geom", 175.1, -90, 181.1, 90, CRS.toSRS(WGS84))
        val binarySpatial = ff.or(spatial1, spatial2)

        val query = new Query(sftName, binarySpatial)
        val results = fs.getFeatures(query)
        results.size() mustEqual 226
      }
    }

    "provide ability to configure auth provider by static auths" in {
      // create the data store
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId" -> "mycloud",
        "zookeepers" -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user" -> "myuser",
        "password" -> "mypassword",
        "auths" -> "user",
        "tableName" -> "testwrite",
        "useMock" -> "true",
        "featureEncoding" -> "avro")).asInstanceOf[AccumuloDataStore]
      ds should not be null
      ds.authorizationsProvider should beAnInstanceOf[FilteringAuthorizationsProvider]
      ds.authorizationsProvider.asInstanceOf[FilteringAuthorizationsProvider].wrappedProvider should beAnInstanceOf[DefaultAuthorizationsProvider]
      ds.authorizationsProvider.asInstanceOf[AuthorizationsProvider].getAuthorizations should be equalTo new Authorizations("user")
    }

    "provide ability to configure auth provider by comma-delimited static auths" in {
      // create the data store
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId" -> "mycloud",
        "zookeepers" -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user" -> "myuser",
        "password" -> "mypassword",
        "auths" -> "user,admin,test",
        "tableName" -> "testwrite",
        "useMock" -> "true",
        "featureEncoding" -> "avro")).asInstanceOf[AccumuloDataStore]
      ds should not be null
      ds.authorizationsProvider should beAnInstanceOf[FilteringAuthorizationsProvider]
      ds.authorizationsProvider.asInstanceOf[FilteringAuthorizationsProvider].wrappedProvider should beAnInstanceOf[DefaultAuthorizationsProvider]
      ds.authorizationsProvider.asInstanceOf[AuthorizationsProvider].getAuthorizations should be equalTo new Authorizations("user", "admin", "test")
    }

    "fail when auth provider system property does not match an actual class" in {
      System.setProperty(AuthorizationsProvider.AUTH_PROVIDER_SYS_PROPERTY, "my.fake.Clas")
      try {
        // create the data store
        DataStoreFinder.getDataStore(Map(
          "instanceId"        -> "mycloud",
          "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
          "user"              -> "myuser",
          "password"          -> "mypassword",
          "auths"             -> "user,admin,test",
          "tableName"         -> "testwrite",
          "useMock"           -> "true",
          "featureEncoding"   -> "avro")) should throwA[IllegalArgumentException]
      } finally System.clearProperty(AuthorizationsProvider.AUTH_PROVIDER_SYS_PROPERTY)
    }

    "create and retrieve a schema" in {
      val sftName = "schematest"
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"        -> "mycloud",
        "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"              -> "myuser",
        "password"          -> "mypassword",
        "auths"             -> "A,B,C",
        "tableName"         -> "schematest",
        "useMock"           -> "true",
        "featureEncoding"   -> "avro")).asInstanceOf[AccumuloDataStore]

      val sft = SimpleFeatureTypes.createType(sftName, s"name:String,dtg:Date,*geom:Point:srid=4326")
      sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
      ds.createSchema(sft)

      val retrievedSft = ds.getSchema(sftName)

      retrievedSft must equalTo(sft)
      retrievedSft.getUserData.get(SF_PROPERTY_START_TIME) must beEqualTo("dtg")
    }

    "create and retrieve a schema with a custom IndexSchema" in {
      val sftName = "schematestCustomSchema"
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"        -> "mycloud",
        "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"              -> "myuser",
        "password"          -> "mypassword",
        "auths"             -> "A,B,C",
        "tableName"         -> "schematest",
        "useMock"           -> "true",
        "featureEncoding"   -> "avro")).asInstanceOf[AccumuloDataStore]

      val indexSchema = buildTestIndexSchemaFormat(sftName)
      val sft = SimpleFeatureTypes.createType(sftName, s"name:String,dtg:Date,*geom:Point:srid=4326")
      sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
      sft.getUserData.put(SFT_INDEX_SCHEMA, indexSchema)
      ds.createSchema(sft)

      val retrievedSft = ds.getSchema(sftName)

      retrievedSft must equalTo(sft)
      retrievedSft.getUserData.get(SF_PROPERTY_START_TIME) must beEqualTo("dtg")
      retrievedSft.getUserData.get(SFT_INDEX_SCHEMA) must beEqualTo(indexSchema)
      getIndexSchema(retrievedSft) must beEqualTo(Option(indexSchema))
    }

    "create and retrieve a schema without a custom IndexSchema" in {
      val sftName = "schematestDefaultSchema"
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"        -> "mycloud",
        "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"              -> "myuser",
        "password"          -> "mypassword",
        "auths"             -> "A,B,C",
        "tableName"         -> "schematest",
        "useMock"           -> "true",
        "featureEncoding"   -> "avro")).asInstanceOf[AccumuloDataStore]

      val sft = SimpleFeatureTypes.createType(sftName, s"name:String,dtg:Date,*geom:Point:srid=4326")
      sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")

      val mockMaxShards = ds.DEFAULT_MAX_SHARD
      val indexSchema = ds.computeSpatioTemporalSchema(sft, mockMaxShards)

      ds.createSchema(sft)

      val retrievedSft = ds.getSchema(sftName)

      mockMaxShards must equalTo(0)
      retrievedSft must equalTo(sft)
      retrievedSft.getUserData.get(SF_PROPERTY_START_TIME) must beEqualTo("dtg")
      retrievedSft.getUserData.get(SFT_INDEX_SCHEMA) must beEqualTo(indexSchema)
      getIndexSchema(retrievedSft) must beEqualTo(Option(indexSchema))
    }

    "allow custom schema metadata if not specified" in {
      // relies on data store created in previous test
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"      -> "mycloud",
        "zookeepers"      -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"            -> "myuser",
        "password"        -> "mypassword",
        "auths"           -> "A,B,C",
        "tableName"       -> "schematest",
        "useMock"         -> "true",
        "featureEncoding" -> "avro")).asInstanceOf[AccumuloDataStore]
      val sftName = "schematest"

      val fr = ds.getFeatureReader(sftName)
      fr should not be null
    }

    "allow users with sufficient auths to write data" in {
      // create the data store
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"        -> "mycloud",
        "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"              -> "myuser",
        "password"          -> "mypassword",
        "auths"             -> "user,admin",
        "visibilities"      -> "user&admin",
        "tableName"         -> "testwrite",
        "useMock"           -> "true",
        "featureEncoding"   -> "avro")).asInstanceOf[AccumuloDataStore]
      ds should not be null

      // create the schema - the auths for this user are sufficient to write data
      val sftName = "authwritetest1"
      val sft = SimpleFeatureTypes.createType(sftName, s"name:String,dtg:Date,*geom:Point:srid=4326")
      sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
      ds.createSchema(sft)

      // write some data
      val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]
      val written = fs.addFeatures(new ListFeatureCollection(sft, getFeatures(sft).toList))

      written should not be null
      written.length mustEqual 6
    }

    "restrict users with insufficient auths from writing data" in {
      // create the data store
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"        -> "mycloud",
        "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"              -> "myuser",
        "password"          -> "mypassword",
        "auths"             -> "user",
        "visibilities"      -> "user&admin",
        "tableName"         -> "testwrite",
        "useMock"           -> "true",
        "featureEncoding"   -> "avro")).asInstanceOf[AccumuloDataStore]
      ds should not be null

      // create the schema - the auths for this user are less than the visibility used to write data
      val sftName = "authwritetest2"
      val sft = SimpleFeatureTypes.createType(sftName, s"name:String,dtg:Date,*geom:Point:srid=4326")
      sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
      ds.createSchema(sft)

      // write some data
      val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]
      try {
        // this should throw an exception
        fs.addFeatures(new ListFeatureCollection(sft, getFeatures(sft).toList))
        failure("Should not be able to write data")
      } catch {
        case e: RuntimeException => success
      }
    }

    "allow users to call explainQuery" >> {
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"      -> "mycloud",
        "zookeepers"      -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"            -> "myuser",
        "password"        -> "mypassword",
        "auths"           -> "A,B,C",
        "tableName"       -> "schematest",
        "useMock"         -> "true",
        "featureEncoding" -> "avro")).asInstanceOf[AccumuloDataStore]
      val sftName = "schematest"

      val query = new Query(sftName, Filter.INCLUDE)
      val fr = ds.getFeatureReader(sftName)
      fr.explainQuery(query)
      fr should not be null
    }

    "allow secondary attribute indexes" >> {
      val table = "testing_secondary_index"
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"        -> "mycloud",
        "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"              -> "myuser",
        "password"          -> "mypassword",
        "tableName"         -> table,
        "useMock"           -> "true")).asInstanceOf[AccumuloDataStore]

      // accumulo supports only alphanum + underscore aka ^\\w+$
      // this should be OK
      val sftName = "somethingsafe3"
      val sft = SimpleFeatureTypes.createType(sftName, s"name:String:index=true,numattr:Integer,dtg:Date,*geom:Point:srid=4326")
      ds.createSchema(sft)

      val mockInstance = new MockInstance("mycloud")
      val c = mockInstance.getConnector("myuser", new PasswordToken("mypassword".getBytes("UTF8")))

      // Shared
      "create all appropriate tables" >> {
        "catalog table" >> { c.tableOperations().exists(table) must beTrue }
        "st_idx table" >> { c.tableOperations().exists(s"${table}_st_idx") must beTrue }
        "records table" >> { c.tableOperations().exists(s"${table}_records") must beTrue }
        "attr idx table" >> { c.tableOperations().exists(s"${table}_attr_idx") must beTrue }
      }

      val pt = gf.createPoint(new Coordinate(0, 0))
      val one = AvroSimpleFeatureFactory.buildAvroFeature(sft, Seq("one", new Integer(1), new DateTime(), pt), "1")
      val two = AvroSimpleFeatureFactory.buildAvroFeature(sft, Seq("two", new Integer(2), new DateTime(), pt), "2")

      val fs = ds.getFeatureSource(sftName).asInstanceOf[SimpleFeatureStore]
      fs.addFeatures(DataUtilities.collection(List(one, two)))
      fs.flush()

      "query indexed attribute" >> {
        val q1 = ff.equals(ff.property("name"), ff.literal("one"))
        val fr = ds.getFeatureReader(sftName, new Query(sftName, q1))
        val results = CloseableIterator(fr).toList
        results must haveLength(1)
        results.head.getAttribute("name") mustEqual "one"
      }

      "query non-indexed attributes" >> {
        val q2 = ff.equals(ff.property("numattr"), ff.literal(2))
        val fr = ds.getFeatureReader(sftName, new Query(sftName, q2))
        val results = CloseableIterator(fr).toList
        results must haveLength(1)
        results.head.getAttribute("numattr") mustEqual 2
      }
    }

    "support caching for improved WFS performance due to count/getFeatures" >> {
      val table = "testing_caching_featureSource"
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"        -> "mycloud",
        "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"              -> "myuser",
        "password"          -> "mypassword",
        "tableName"         -> table,
        "caching"           -> true,
        "useMock"           -> "true")).asInstanceOf[AccumuloDataStore]

      val sftName = "testingCaching"
      val sft = SimpleFeatureTypes.createType(sftName, s"name:String:index=true,numattr:Integer,dtg:Date,*geom:Point:srid=4326")
      ds.createSchema(sft)

      val mockInstance = new MockInstance("mycloud")
      val c = mockInstance.getConnector("myuser", new PasswordToken("mypassword".getBytes("UTF8")))

      "typeOf feature source must be ListFeatureCollection" >> {
        val fc = ds.getFeatureSource(sftName).getFeatures(Filter.INCLUDE)
        fc must haveClass[CachingAccumuloFeatureCollection]
      }
    }

    "hex encode multibyte chars as multiple underscore + hex" in {
      val table = "testing_chinese_features"
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"        -> "mycloud",
        "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"              -> "myuser",
        "password"          -> "mypassword",
        "tableName"         -> table,
        "useMock"           -> "true")).asInstanceOf[AccumuloDataStore]

      ds should not be null

      // accumulo supports only alphanum + underscore aka ^\\w+$
      // this should end up hex encoded
      val sftName = "nihao你好"
      val sft = SimpleFeatureTypes.createType(sftName, s"name:String,dtg:Date,*geom:Point:srid=4326")
      org.locationtech.geomesa.core.index.setTableSharing(sft, false)
      ds.createSchema(sft)

      val mockInstance = new MockInstance("mycloud")
      val c = mockInstance.getConnector("myuser", new PasswordToken("mypassword".getBytes("UTF8")))

      // encode groups of 2 hex chars since we are doing multibyte chars
      def enc(s: String): String = Hex.encodeHex(s.getBytes("UTF8")).grouped(2)
        .map{ c => "_" + c(0) + c(1) }.mkString.toLowerCase

      // three byte UTF8 chars result in 9 char string
      enc("你").length mustEqual 9
      enc("好").length mustEqual 9

      val encodedSFT = "nihao" + enc("你") + enc("好")
      encodedSFT mustEqual AccumuloDataStore.hexEncodeNonAlphaNumeric(sftName)

      AccumuloDataStore.formatSpatioTemporalIdxTableName(table, sft) mustEqual s"${table}_${encodedSFT}_st_idx"
      AccumuloDataStore.formatRecordTableName(table, sft) mustEqual s"${table}_${encodedSFT}_records"
      AccumuloDataStore.formatAttrIdxTableName(table, sft) mustEqual s"${table}_${encodedSFT}_attr_idx"

      c.tableOperations().exists(table) must beTrue
      c.tableOperations().exists(s"${table}_${encodedSFT}_st_idx") must beTrue
      c.tableOperations().exists(s"${table}_${encodedSFT}_records") must beTrue
      c.tableOperations().exists(s"${table}_${encodedSFT}_attr_idx") must beTrue
    }

    "delete the schema completely" in {
      val table = "testing_delete_schema"
      val sftName = "test"
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"        -> "mycloud",
        "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"              -> "myuser",
        "password"          -> "mypassword",
        "tableName"         -> table,
        "useMock"           -> "true")).asInstanceOf[AccumuloDataStore]

      ds should not be null

      val fs = getFeatureStore(sftName, ds, false)

      val mockInstance = new MockInstance("mycloud")
      val c = mockInstance.getConnector("myuser", new PasswordToken("mypassword".getBytes("UTF8")))

      //tests that tables exist before being deleted
      c.tableOperations().exists(s"${table}_${sftName}_st_idx") must beTrue
      c.tableOperations().exists(s"${table}_${sftName}_records") must beTrue
      c.tableOperations().exists(s"${table}_${sftName}_attr_idx") must beTrue

      val fr = ds.getFeatureReader(sftName)
      //tests that metadata exists in the catalog before being deleted
      fr should not be null

      val scannerResults = getScannerResults(ds, sftName)
      scannerResults should beSome

      ds.removeSchema(sftName)

      //tables should be deleted now (for stand-alone tables only)
      c.tableOperations().exists(s"${table}_${sftName}_st_idx") must beFalse
      c.tableOperations().exists(s"${table}_${sftName}_records") must beFalse
      c.tableOperations().exists(s"${table}_${sftName}_attr_idx") must beFalse

      val scannerResultsAfterDeletion = getScannerResults(ds, sftName)

      //metadata should be deleted from the catalog now
      scannerResultsAfterDeletion should beNone

      val query = new Query(sftName, Filter.INCLUDE)
      fs.getFeatures(query) must throwA[Exception]
    }

    "throw a RuntimeException when calling removeSchema on 0.10.x records" in {
      val manualParams = Map(
        "instanceId" -> "mycloud",
        "zookeepers" -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"       -> "myuser",
        "password"   -> "mypassword",
        "auths"      -> "A,B,C",
        "useMock"    -> "true",
        "tableName"  -> "manualTableForDeletion")
      val sftName = "regressionTestType"

      buildPreSecondaryIndexTable(manualParams, sftName)

      val manualStore = DataStoreFinder.getDataStore(manualParams).asInstanceOf[AccumuloDataStore]
      manualStore.removeSchema(sftName) should throwA[RuntimeException]
    }

    "keep other tables when a separate schema is deleted" in {
      val table = "testing_delete_schema"
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"        -> "mycloud",
        "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"              -> "myuser",
        "password"          -> "mypassword",
        "tableName"         -> table,
        "useMock"           -> "true")).asInstanceOf[AccumuloDataStore]

      ds should not be null

      val sftName = "test"
      val sftName2 = "test2"

      val fs = getFeatureStore(sftName, ds, false)
      val fs2 = getFeatureStore(sftName2, ds, false)

      val mockInstance = new MockInstance("mycloud")
      val c = mockInstance.getConnector("myuser", new PasswordToken("mypassword".getBytes("UTF8")))

      //tests that tables exist before being deleted
      c.tableOperations().exists(s"${table}_${sftName}_st_idx") must beTrue
      c.tableOperations().exists(s"${table}_${sftName}_records") must beTrue
      c.tableOperations().exists(s"${table}_${sftName}_attr_idx") must beTrue
      c.tableOperations().exists(s"${table}_${sftName2}_st_idx") must beTrue
      c.tableOperations().exists(s"${table}_${sftName2}_records") must beTrue
      c.tableOperations().exists(s"${table}_${sftName2}_attr_idx") must beTrue

      val fr = ds.getFeatureReader(sftName)
      val fr2 = ds.getFeatureReader(sftName2)
      //tests that metadata exists in the catalog before being deleted
      fr should not be null
      fr2 should not be null

      val scannerResults = getScannerResults(ds, sftName)
      val scannerResults2 = getScannerResults(ds, sftName2)
      scannerResults should beSome
      scannerResults2 should beSome

      ds.removeSchema(sftName)

      //these tables should be deleted now
      c.tableOperations().exists(s"${table}_${sftName}_st_idx") must beFalse
      c.tableOperations().exists(s"${table}_${sftName}_records") must beFalse
      c.tableOperations().exists(s"${table}_${sftName}_attr_idx") must beFalse
      //but these tables should still exist since sftName2 wasn't deleted
      c.tableOperations().exists(s"${table}_${sftName2}_st_idx") must beTrue
      c.tableOperations().exists(s"${table}_${sftName2}_records") must beTrue
      c.tableOperations().exists(s"${table}_${sftName2}_attr_idx") must beTrue

      val scannerResultsAfterDeletion = getScannerResults(ds, sftName)
      val scannerResultsAfterDeletion2 = getScannerResults(ds, sftName2)

      //metadata should be deleted from the catalog now for sftName
      scannerResultsAfterDeletion should beNone
      //metadata should still exist for sftName2
      scannerResultsAfterDeletion2 should beSome

      val query = new Query(sftName, Filter.INCLUDE)
      val query2 = new Query(sftName2, Filter.INCLUDE)

      val results2 = fs2.getFeatures(query2)
      results2.size() should beGreaterThan(0)
    }

    "update metadata for indexed attributes" in {
      val originalSchema = "name:String,dtg:Date,*geom:Point:srid=4326:index=true"
      val updatedSchema = s"name:String:index=true,dtg:Date,*geom:Point:srid=4326:index=true:$OPT_INDEX_VALUE=true"
      val ds = createStore
      val sft = SimpleFeatureTypes.createType("test", originalSchema)
      ds.createSchema(sft)
      ds.updateIndexedAttributes("test", updatedSchema)
      val retrievedSchema = SimpleFeatureTypes.encodeType(ds.getSchema("test"))
      retrievedSchema mustEqual updatedSchema
    }

    "prevent changing schema types" in {
      val originalSchema = s"name:String,dtg:Date,*geom:Point:srid=4326:index=true:$OPT_INDEX_VALUE=true"
      val ds = createStore
      val sft = SimpleFeatureTypes.createType("test", originalSchema)
      ds.createSchema(sft)

      "prevent changing default geometry" in {
        val updatedSchema = "name:String,dtg:Date,geom:Point:srid=4326"
        ds.updateIndexedAttributes("test", updatedSchema) should throwA[IllegalArgumentException]
        val retrievedSchema = SimpleFeatureTypes.encodeType(ds.getSchema("test"))
        retrievedSchema mustEqual originalSchema
      }

      "prevent changing attribute order" in {
        val updatedSchema = "dtg:Date,name:String,*geom:Point:srid=4326"
        ds.updateIndexedAttributes("test", updatedSchema) should throwA[IllegalArgumentException]
        val retrievedSchema = SimpleFeatureTypes.encodeType(ds.getSchema("test"))
        retrievedSchema mustEqual originalSchema
      }

      "prevent adding attributes" in {
        val updatedSchema = "name:String,dtg:Date,*geom:Point:srid=4326,newField:String"
        ds.updateIndexedAttributes("test", updatedSchema) should throwA[IllegalArgumentException]
        val retrievedSchema = SimpleFeatureTypes.encodeType(ds.getSchema("test"))
        retrievedSchema mustEqual originalSchema
      }

      "prevent removing attributes" in {
        val updatedSchema = "dtg:Date,*geom:Point:srid=4326"
        ds.updateIndexedAttributes("test", updatedSchema) should throwA[IllegalArgumentException]
        val retrievedSchema = SimpleFeatureTypes.encodeType(ds.getSchema("test"))
        retrievedSchema mustEqual originalSchema
      }
    }

    "Provide a feature update implementation" >> {
      val sftName = "featureUpdateTest"
      val sft = SimpleFeatureTypes.createType(sftName, "name:String,dtg:Date,*geom:Point:srid=4326")
      val ds = DataStoreFinder.getDataStore(Map(
        "instanceId"        -> "mycloud",
        "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
        "user"              -> "myuser",
        "password"          -> "mypassword",
        "tableName"         -> sftName,
        "useMock"           -> "true"))
      ds.createSchema(sft)
      val builder = AvroSimpleFeatureFactory.featureBuilder(ds.getSchema(sftName))
      val features = (0 until 6).map { i =>
        builder.reset()
        builder.set("geom", WKTUtils.read("POINT(45.0 45.0)"))
        builder.set("dtg", "2012-01-02T05:06:07.000Z")
        builder.set("name",i.toString)
        val sf = builder.buildFeature(i.toString)
        sf.getUserData()(Hints.USE_PROVIDED_FID) = java.lang.Boolean.TRUE
        sf
      }
      val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]
      fs.addFeatures(new ListFeatureCollection(sft, features))

      val filter = ff.id(ff.featureId("2"))
      val writer = ds.getFeatureWriter(sftName, filter, Transaction.AUTO_COMMIT)
      writer.hasNext must beTrue
      val feat = writer.next
      feat.getID mustEqual "2"
      feat.getAttribute("name") mustEqual "2"
      feat.setAttribute("name", "2-updated")
      writer.write()
      writer.hasNext must beFalse
      writer.close()

      val reader = ds.getFeatureReader(new Query(sftName, filter), Transaction.AUTO_COMMIT)
      reader.hasNext must beTrue
      val updated = reader.next()
      reader.hasNext must beFalse
      reader.close()
      updated.getID mustEqual("2")
      updated.getAttribute("name") mustEqual "2-updated"
    }

    "Allow extra attributes in the STIDX entries" >> {
      val sftName = "STIDXExtraAttributeTest"
      val sft = SimpleFeatureTypes.createType(sftName,
        s"name:String:$OPT_INDEX_VALUE=true,dtg:Date:$OPT_INDEX_VALUE=true,*geom:Point:srid=4326,attr2:String")
      val ds = createSchema(sft)

      val builder = AvroSimpleFeatureFactory.featureBuilder(sft)
      val features = (0 until 6).map { i =>
        builder.set("geom", WKTUtils.read(s"POINT(45.0 4$i.0)"))
        builder.set("dtg", s"2012-01-02T05:0$i:07.000Z")
        builder.set("name", i.toString)
        builder.set("attr2", "2-" + i.toString)
        val sf = builder.buildFeature(i.toString)
        sf.getUserData.update(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
        sf
      }

      val baseTime = features(0).getAttribute("dtg").asInstanceOf[Date].getTime

      val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]
      fs.addFeatures(new ListFeatureCollection(sft, features))

      val query = new Query(sftName, ECQL.toFilter("BBOX(geom, 40.0, 40.0, 50.0, 50.0)"),
        Array("geom", "dtg", "name"))
      val reader = ds.getFeatureReader(sftName, query)

      // verify that the IndexIterator is getting used with the extra field
      val explain = {
        val out = new ExplainString
        reader.explainQuery(o = out)
        out.toString()
      }
      explain must contain(classOf[IndexIterator].getName)

      val read = SelfClosingIterator(reader).toList

      // verify that all the attributes came back
      read must haveSize(6)
      read.sortBy(_.getAttribute("name").asInstanceOf[String]).zipWithIndex.foreach { case (sf, i) =>
        sf.getAttributeCount mustEqual 3
        sf.getAttribute("name") mustEqual i.toString
        sf.getAttribute("geom") mustEqual WKTUtils.read(s"POINT(45.0 4$i.0)")
        sf.getAttribute("dtg").asInstanceOf[Date].getTime mustEqual baseTime + i * 60000
      }
      success
    }

    "Use IndexIterator when projecting to date/geom" >> {
      val sftName = "STIDXExtraAttributeTest2"
      val sft = SimpleFeatureTypes.createType(sftName,
        s"name:String:$OPT_INDEX_VALUE=true,dtg:Date:$OPT_INDEX_VALUE=true,*geom:Point:srid=4326,attr2:String")
      val ds = createSchema(sft)

      val builder = AvroSimpleFeatureFactory.featureBuilder(sft)
      val features = (0 until 6).map { i =>
        builder.set("geom", WKTUtils.read(s"POINT(45.0 4$i.0)"))
        builder.set("dtg", s"2012-01-02T05:0$i:07.000Z")
        builder.set("name", i.toString)
        builder.set("attr2", "2-" + i.toString)
        val sf = builder.buildFeature(i.toString)
        sf.getUserData.update(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
        sf
      }

      val baseTime = features(0).getAttribute("dtg").asInstanceOf[Date].getTime

      val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]
      fs.addFeatures(new ListFeatureCollection(sft, features))

      val query = new Query(sftName, ECQL.toFilter("BBOX(geom, 40.0, 40.0, 50.0, 50.0)"),
        Array("geom", "dtg"))
      val reader = ds.getFeatureReader(sftName, query)

      // verify that the IndexIterator is getting used
      val explain = {
        val out = new ExplainString
        reader.explainQuery(o = out)
        out.toString()
      }
      explain must contain(classOf[IndexIterator].getName)

      val read = SelfClosingIterator(reader).toList

      // verify that all the attributes came back
      read must haveSize(6)
      read.sortBy(_.getAttribute("dtg").toString).zipWithIndex.foreach { case (sf, i) =>
        sf.getAttributeCount mustEqual 2
        sf.getAttribute("name") must beNull
        sf.getAttribute("geom") mustEqual WKTUtils.read(s"POINT(45.0 4$i.0)")
        sf.getAttribute("dtg").asInstanceOf[Date].getTime mustEqual baseTime + i * 60000
      }
      success
    }
  }

  "AccumuloFeatureStore" should {
    val ds = createStore
    "compute target schemas from transformation expressions" in {
      val origSFT = SimpleFeatureTypes.createType("test", "name:String,dtg:Date,*geom:Point:srid=4326")
      origSFT.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
      val definitions =
        TransformProcess.toDefinition("name=name;helloName=strConcat('hello', name);geom=geom")

      val result = AccumuloFeatureStore.computeSchema(origSFT, definitions.toSeq)
      println(SimpleFeatureTypes.encodeType(result))

      (result must not).beNull
    }

    "support sorting and handle time bounds" >> {
      val sft = SimpleFeatureTypes.createType("test", "name:String,dtg:Date,*geom:Point:srid=4326")
      sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")

      ds.createSchema(sft)
      val fs = ds.getFeatureSource("test").asInstanceOf[SimpleFeatureStore]
      fs.getQueryCapabilities.supportsSorting(Array(SortBy.NATURAL_ORDER)) must beTrue
      fs.getQueryCapabilities.supportsSorting(Array(ff.sort("dtg", SortOrder.ASCENDING))) must beTrue

      val defaultInterval = ds.getTimeBounds(sft.getTypeName)
      defaultInterval.getStartMillis must be equalTo 0

      val sfBuilder = new SimpleFeatureBuilder(sft)
      sfBuilder.reset()
      val date1 = new DateTime("2014-01-02").toDate
      sfBuilder.addAll(List("johndoe", date1, gf.createPoint(new Coordinate(0, 0))))
      val f1 = sfBuilder.buildFeature("f1")

      fs.addFeatures(DataUtilities.collection(List(f1)))

      val secondInterval = ds.getTimeBounds(sft.getTypeName)
      secondInterval.getStartMillis must be equalTo date1.getTime
      secondInterval.getEndMillis must be equalTo date1.getTime

      sfBuilder.reset()
      val date2 = new DateTime("2014-01-03").toDate
      sfBuilder.addAll(List("johndoe", date2, gf.createPoint(new Coordinate(0, 0))))
      val f2 = sfBuilder.buildFeature("f2")

      sfBuilder.reset()
      val date3 = new DateTime("2014-01-01").toDate
      sfBuilder.addAll(List("johndoe", date3 , gf.createPoint(new Coordinate(0, 0))))
      val f3 = sfBuilder.buildFeature("f3")

      fs.addFeatures(DataUtilities.collection(List(f2, f3)))
      fs.flush()

      val thirdInterval = ds.getTimeBounds(sft.getTypeName)
      thirdInterval.getStartMillis must be equalTo date3.getTime
      thirdInterval.getEndMillis must be equalTo date2.getTime

      "ascending on date" >> {
        val dtgAscendingQ = new Query("test", Filter.INCLUDE)
        dtgAscendingQ.setSortBy(Array(ff.sort("dtg", SortOrder.ASCENDING)))

        val res: Seq[Long] = fs.getFeatures(dtgAscendingQ).features().toIterator.map(_.getAttribute("dtg").asInstanceOf[Date].getTime).toSeq
        res must beSorted
        res.head must beLessThan(res(1))
      }

      "descending on date" >> {
        val dtgDescending = new Query("test", Filter.INCLUDE)
        dtgDescending.setSortBy(Array(ff.sort("dtg", SortOrder.DESCENDING)))

        val res: Seq[Long] = fs.getFeatures(dtgDescending).features().toIterator.map(_.getAttribute("dtg").asInstanceOf[Date].getTime).toSeq
        res.reverse must beSorted
        res.head must beGreaterThan(res(1))
      }
    }
  }

  def buildTestIndexSchemaFormat(featureName: String) = new IndexSchemaBuilder("~").randomNumber(3).constant(featureName).geoHash(0, 3).date("yyyyMMdd").nextPart().geoHash(3, 2).nextPart().id().build()

  def createSchema(sft: SimpleFeatureType) = {
    val ds = DataStoreFinder.getDataStore(Map(
      "instanceId"        -> "mycloud",
      "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
      "user"              -> "myuser",
      "password"          -> "mypassword",
      "tableName"         -> sft.getTypeName,
      "useMock"           -> "true"))
    ds.createSchema(sft)
    ds.asInstanceOf[AccumuloDataStore]
  }

  def createStore: AccumuloDataStore = {
    // need to add a unique ID, otherwise create schema will throw an exception
    id = id + 1
    // the specific parameter values should not matter, as we
    // are requesting a mock data store connection to Accumulo
    DataStoreFinder.getDataStore(Map(
      "instanceId"        -> "mycloud",
      "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
      "user"              -> "myuser",
      "password"          -> "mypassword",
      "auths"             -> "A,B,C",
      "tableName"         -> f"testwrite$id%d",
      "useMock"           -> "true",
      "featureEncoding"   -> "avro")).asInstanceOf[AccumuloDataStore]
  }

  def getFeatureStore(sftName: String, ds: AccumuloDataStore, sharedTables: Boolean = true): AccumuloFeatureStore = {
    val sft = SimpleFeatureTypes.createType(sftName, s"name:String,dtg:Date,*geom:Point:srid=4326")
    org.locationtech.geomesa.core.index.setTableSharing(sft, sharedTables)
    ds.createSchema(sft)
    val fs = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]
    val geom = WKTUtils.read("POINT(45.0 49.0)")
    val builder = new SimpleFeatureBuilder(sft, featureFactory)
    builder.addAll(List(sftName, null, geom))
    val liveFeature = builder.buildFeature("fid-1")

    liveFeature.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
    val featureCollection = new DefaultFeatureCollection(sftName, sft)
    featureCollection.add(liveFeature)
    fs.addFeatures(featureCollection)
    fs
  }

  /**
   * Executes a scan for metadata information in the catalog
   *
   * @param ds the Accumulo datastore
   * @param sftName the name of the SimpleFeatureType
   */
  def getScannerResults(ds: AccumuloDataStore, sftName: String): Option[String] = {
    val scanner = ds.connector.createScanner(ds.catalogTable, ds.authorizationsProvider.getAuthorizations)
    scanner.setRange(new Range(s"${METADATA_TAG }_$sftName"))

    val name = "version-" + sftName
    val cfg = new IteratorSetting(1, name, classOf[VersioningIterator])
    VersioningIterator.setMaxVersions(cfg, 1)
    scanner.addScanIterator(cfg)

    val iter = scanner.iterator
    val result =
      if (iter.hasNext) {
        Some(iter.next.getValue.toString)
      } else {
        None
      }

    scanner.close()
    scanner.removeScanIterator(name)
    result
  }

  def buildPreSecondaryIndexTable(params: Map[String, String], sftName: String) = {
    val rowIds = List(
      "09~regressionTestType~v00~20120102",
      "95~regressionTestType~v00~20120102",
      "53~regressionTestType~v00~20120102",
      "77~regressionTestType~v00~20120102",
      "36~regressionTestType~v00~20120102",
      "91~regressionTestType~v00~20120102")
    val hex = new Hex
    val indexValues = List(
      "000000013000000015000000000140468000000000004046800000000000000001349ccf6e18",
      "000000013100000015000000000140468000000000004046800000000000000001349ccf6e18",
      "000000013200000015000000000140468000000000004046800000000000000001349ccf6e18",
      "000000013300000015000000000140468000000000004046800000000000000001349ccf6e18",
      "000000013400000015000000000140468000000000004046800000000000000001349ccf6e18",
      "000000013500000015000000000140468000000000004046800000000000000001349ccf6e18").map {v =>
      hex.decode(v.getBytes)}
    val sft = SimpleFeatureTypes.createType(sftName, s"name:String,$geotimeAttributes")
    sft.getUserData.put(SF_PROPERTY_START_TIME, "dtg")

    val instance = new MockInstance(params("instanceId"))
    val connector = instance.getConnector(params("user"), new PasswordToken(params("password").getBytes))
    connector.tableOperations.create(params("tableName"))

    val bw = connector.createBatchWriter(params("tableName"), new BatchWriterConfig)

    // Insert metadata
    val metadataMutation = new Mutation(s"~METADATA_$sftName")
    metadataMutation.put("attributes", "", "name:String,geom:Geometry:srid=4326,dtg:Date,dtg_end_time:Date")
    metadataMutation.put("bounds", "", "45.0:45.0:49.0:49.0")
    metadataMutation.put("schema", "", s"%~#s%99#r%$sftName#cstr%0,3#gh%yyyyMMdd#d::%~#s%3,2#gh::%~#s%#id")
    bw.addMutation(metadataMutation)

    // Insert features
    getFeatures(sft).zipWithIndex.foreach { case(sf, idx) =>
      val encoded = DataUtilities.encodeFeature(sf)
      val index = new Mutation(rowIds(idx))
      index.put("00".getBytes,sf.getID.getBytes, indexValues(idx))
      bw.addMutation(index)

      val data = new Mutation(rowIds(idx))
      data.put(sf.getID, "SimpleFeatureAttribute", encoded)
      bw.addMutation(data)
    }

    bw.flush
    bw.close
  }

  def getFeatures(sft: SimpleFeatureType) = (0 until 6).map { i =>
    val builder = new SimpleFeatureBuilder(sft, featureFactory)
    builder.set("geom", WKTUtils.read("POINT(45.0 45.0)"))
    builder.set("dtg", "2012-01-02T05:06:07.000Z")
    builder.set("name",i.toString)
    val sf = builder.buildFeature(i.toString)
    sf.getUserData()(Hints.USE_PROVIDED_FID) = java.lang.Boolean.TRUE
    sf
  }


}
