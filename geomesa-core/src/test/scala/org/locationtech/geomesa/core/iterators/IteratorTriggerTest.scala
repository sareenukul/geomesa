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

package org.locationtech.geomesa.core.iterators

import org.geotools.data.Query
import org.geotools.filter.text.cql2.CQL
import org.geotools.filter.text.ecql.ECQL
import org.junit.runner.RunWith
import org.locationtech.geomesa.core.data._
import org.locationtech.geomesa.core.filter._
import org.locationtech.geomesa.core.index.FilterHelper._
import org.locationtech.geomesa.core.index._
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes._
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.Filter
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class IteratorTriggerTest extends Specification {
  sequential

  object TestTable {
    val TEST_TABLE = "test_table"
    val featureName = "feature"
    val schemaEncoding = "%~#s%" + featureName + "#cstr%10#r%0,1#gh%yyyyMM#d::%~#s%1,3#gh::%~#s%4,3#gh%ddHH#d%10#id"

    val testFeatureTypeSpec: String = {
      s"POINT:String,LINESTRING:String,POLYGON:String,attr1:String:$OPT_INDEX_VALUE=true,attr2:String," + spec
    }

    val testFeatureType: SimpleFeatureType = {
      val featureType: SimpleFeatureType = SimpleFeatureTypes.createType(featureName, testFeatureTypeSpec)
      featureType.getUserData.put(SF_PROPERTY_START_TIME, "dtg")
      featureType
    }


    def sampleQuery(ecql: org.opengis.filter.Filter, finalAttributes: Array[String]): Query = {
      val aQuery = new Query(testFeatureType.getTypeName, ecql, finalAttributes)
      AccumuloDataStore.setQueryTransforms(aQuery, testFeatureType) // normally called by data store when getting feature reader
      aQuery
    }

    /**
     * Function that duplicates the filter mutation from StIdxStrategy
     *
     * This will attempt to factor out the time and space components of the ECQL query.
     */

    def extractReWrittenCQL(query: Query, featureType: SimpleFeatureType): Option[Filter] = {
      val (_, otherFilters) = partitionGeom(query.getFilter)
      val (_, ecqlFilters: Seq[Filter]) = partitionTemporal(otherFilters, getDtgFieldName(featureType))

      filterListAsAnd(ecqlFilters)
    }
  }

  object TriggerTest {
    // filters for testing

    val trivialFilterString = "true = true"

    val anotherTrivialFilterString = "(INCLUDE)"

    val spatialFilterString =
      "WITHIN(geom, POLYGON ((45 23, 48 23, 48 27, 45 27, 45 23)))"

    val spatialTemporalFilterString =
      "WITHIN(geom, POLYGON ((45 23, 48 23, 48 27, 45 27, 45 23))) AND (dtg before 2010-08-08T23:59:59Z)"

    val extraAttributeFilterString =
      "WITHIN(geom, POLYGON ((45 23, 48 23, 48 27, 45 27, 45 23))) AND (attr2 like '2nd___')"

    val nonReducibleFilterString =
      "WITHIN(geom, POLYGON ((45 23, 48 23, 48 27, 45 27, 45 23))) AND (dtg before 2010-08-08T23:59:59Z) AND (dtg_end_time after 2010-08-08T00:00:00Z)"

    val reducibleFilterString =
      "WITHIN(geom, POLYGON ((45 23, 48 23, 48 27, 45 27, 45 23))) AND (dtg between '2010-08-08T00:00:00.000Z' AND '2010-08-08T23:59:59.000Z')"

    // transforms for testing
    val geomTransformToIndex = {
      Array("geom")
    }
    val simpleTransformToIndex = {
      Array("geom", "dtg")
    }
    val renameTransformToIndex = {
      Array("newgeo=geom", "dtg")
    }
    val complexTransformToIndex = {
      Array("geom=buffer(geom,2)", "dtg")
    }
    val simpleTransformToIndexPlusAnother = {
      Array("geom", "dtg", "attr2")
    }
    val extraTransformToIndex = {
      Array("geom", "dtg", "attr1")
    }
    val nullTransform = null

    /**
     * Function for use in testing useIndexOnlyIterator
     */
    def useIndexOnlyIteratorTest(ecqlPred: String, transformText: Array[String]): Boolean = {
      val aQuery = TestTable.sampleQuery(ECQL.toFilter(ecqlPred), transformText)
      val modECQLPred = TestTable.extractReWrittenCQL(aQuery, TestTable.testFeatureType)
      IteratorTrigger.useIndexOnlyIterator(modECQLPred, aQuery, TestTable.testFeatureType)
    }

    /**
     * Function for use in testing useSimpleFeatureFilteringIterator
     */
    def useSimpleFeatureFilteringIteratorTest(ecqlPred: String, transformText: Array[String]): Boolean = {
      val aQuery = TestTable.sampleQuery(ECQL.toFilter(ecqlPred), transformText)
      val modECQLPred = TestTable.extractReWrittenCQL(aQuery, TestTable.testFeatureType)
      IteratorTrigger.useSimpleFeatureFilteringIterator(modECQLPred, aQuery)
    }

    /**
     * Function for use in testing chooseIterator
     */
    def chooseIteratorTest(ecqlPred: String, transformText: Array[String]): IteratorConfig = {
      val aQuery = TestTable.sampleQuery(ECQL.toFilter(ecqlPred), transformText)
      val modECQLPred = TestTable.extractReWrittenCQL(aQuery, TestTable.testFeatureType).map(CQL.toCQL)
      IteratorTrigger.chooseIterator(modECQLPred, aQuery, TestTable.testFeatureType)
    }
  }
    "useIndexOnlyIterator" should {
      "be run when requesting only index attributes" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.anotherTrivialFilterString, TriggerTest.simpleTransformToIndex)
        isTriggered must beTrue
      }

      "be run when renaming only index attributes" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.anotherTrivialFilterString, TriggerTest.renameTransformToIndex)
        isTriggered must beTrue
      }

      "not be run when transforming an index attribute" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.anotherTrivialFilterString, TriggerTest.complexTransformToIndex)
        isTriggered must beFalse
      }

      "not be run when requesting a non-index attribute" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.anotherTrivialFilterString, TriggerTest.simpleTransformToIndexPlusAnother)
        isTriggered must beFalse
      }

      "be run when requesting an extra indexed attribute" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.anotherTrivialFilterString, TriggerTest.extraTransformToIndex)
        isTriggered must beTrue
      }

      "not be run when requesting all attributes via a null transform" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.anotherTrivialFilterString, TriggerTest.nullTransform)
        isTriggered must beFalse
      }

      "be run when requesting index attributes and using a trivial filter" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.trivialFilterString, TriggerTest.simpleTransformToIndex)
        isTriggered must beTrue
      }

      "be run when requesting index attributes and using another trivial filter" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.anotherTrivialFilterString, TriggerTest.simpleTransformToIndex)
        isTriggered must beTrue
      }

      "not be run when requesting index attributes and filtering on a non-index attribute" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.extraAttributeFilterString, TriggerTest.simpleTransformToIndex)
        isTriggered must beFalse
      }

      "not be run when requesting index attributes and filtering on a non-index attribute" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.extraAttributeFilterString, TriggerTest.simpleTransformToIndex)
        isTriggered must beFalse
      }

      "not be run when requesting index attributes and dealing with a filter that can not be fully reduced" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.nonReducibleFilterString, TriggerTest.simpleTransformToIndex)
        isTriggered must beFalse
      }

      "be run when requesting index attributes and dealing with a filter that can be fully reduced" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.reducibleFilterString, TriggerTest.simpleTransformToIndex)
        isTriggered must beTrue
      }

      "be run when transforms overlap filters" in {
        val isTriggered = TriggerTest.useIndexOnlyIteratorTest(TriggerTest.spatialTemporalFilterString, TriggerTest.geomTransformToIndex)
        isTriggered must beTrue
      }
    }


  "SimpleFeatureFilteringIterator" should {
    "be run when requesting a transform" in {
       val isTriggered = TriggerTest.useSimpleFeatureFilteringIteratorTest(TriggerTest.anotherTrivialFilterString, TriggerTest.complexTransformToIndex)
       isTriggered must beTrue
    }

    "be run when passed a non-trivial ECQL filter and a simple transform" in {
      val isTriggered = TriggerTest.useSimpleFeatureFilteringIteratorTest(TriggerTest.extraAttributeFilterString, TriggerTest.simpleTransformToIndex)
      isTriggered must beTrue
    }

    "be run when passed a non-trivial ECQL filter and a null transform" in {
      val isTriggered = TriggerTest.useSimpleFeatureFilteringIteratorTest(TriggerTest.extraAttributeFilterString, TriggerTest.nullTransform)
      isTriggered must beTrue
    }

    "not be run when passed a trivial ECQL filter and a null transform" in {
      val isTriggered = TriggerTest.useSimpleFeatureFilteringIteratorTest(TriggerTest.anotherTrivialFilterString, TriggerTest.nullTransform)
      isTriggered must beFalse
   }

    "not be run when transforms overlap filters" in {
      val choice = TriggerTest.chooseIteratorTest(TriggerTest.spatialTemporalFilterString, TriggerTest.simpleTransformToIndex)
      choice.useSFFI must beFalse
    }

    "not be run for geom transform and filter" in {
      val choice = TriggerTest.chooseIteratorTest(TriggerTest.spatialFilterString, TriggerTest.geomTransformToIndex)
      choice.useSFFI must beFalse
    }

    "be run when transforms don't overlap filters" in {
      val choice = TriggerTest.chooseIteratorTest(TriggerTest.spatialTemporalFilterString, TriggerTest.geomTransformToIndex)
      choice.useSFFI must beTrue
    }
  }

  "IteratorTrigger" should {
    "accept INCLUDE as a pass through filter" in {
      IteratorTrigger.passThroughFilter(Filter.INCLUDE) mustEqual(true)
    }

    "determine overlap between transforms and filters" >> {
      val sft = SimpleFeatureTypes.createType("overlaptest", "name:String,dtg:Date,*geom:Point:srid=4326")

      def testOverlap(filter: String, attributes: Array[String]) = {
        val query = new Query("overlaptest", ECQL.toFilter(filter), attributes)
        AccumuloDataStore.setQueryTransforms(query, sft)
        IteratorTrigger.doTransformsCoverFilters(query)
      }

      "for single geom attribute" >> {
        val result = testOverlap("BBOX(geom, -180, -90, 180, 90)", Array("geom"))
        result must beTrue
      }

      "for multiple overlapping attributes" >> {
        val filter = "BBOX(geom, -180, -90, 180, 90) AND dtg = 2010-08-08T23:59:59Z OR name = 'joe'"
        val attributes = Array("geom", "dtg", "name")
        val result = testOverlap(filter, attributes)
        result must beTrue
      }

      "for missing attributes" >> {
        val filter = "BBOX(geom, -180, -90, 180, 90) AND dtg = 2010-08-08T23:59:59Z OR name = 'joe'"
        val attributes = Array("geom", "dtg")
        val result = testOverlap(filter, attributes)
        result must beFalse
      }

      "for non overlapping transforms" >> {
        val filter = "BBOX(geom, -180, -90, 180, 90) AND dtg = 2010-08-08T23:59:59Z"
        val attributes = Array("geom")
        val result = testOverlap(filter, attributes)
        result must beFalse
      }

      "for non-included geoms" >> {
        // geom will always get added to the transforms
        val filter = "BBOX(geom, -180, -90, 180, 90) AND dtg = 2010-08-08T23:59:59Z"
        val attributes = Array("dtg")
        val result = testOverlap(filter, attributes)
        result must beTrue
      }
    }
  }

  "AttributeIndexIterator" should {
    val sftName = "test"
    val spec = "name:String:index=true,age:Integer:index=true,dtg:Date:index=true,*geom:Geometry:srid=4326"
    val sft = SimpleFeatureTypes.createType(sftName, spec)

    "be run when requesting simple attributes" in {
      val query = new Query(sftName, Filter.INCLUDE, Array("geom", "dtg", "name"))
      AccumuloDataStore.setQueryTransforms(query, sft) // normally called by data store when getting feature reader
      val iteratorChoice = IteratorTrigger.chooseAttributeIterator(None, query, sft, "name")
      iteratorChoice.iterator mustEqual(IndexOnlyIterator)
    }
  }
}
