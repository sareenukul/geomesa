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
package org.locationtech.geomesa.tools.commands

import com.beust.jcommander.JCommander
import com.typesafe.scalalogging.slf4j.Logging
import org.locationtech.geomesa.core.data.extractDtgField
import org.locationtech.geomesa.tools.DataStoreHelper
import org.locationtech.geomesa.tools.commands.DescribeCommand._
import org.opengis.feature.`type`.AttributeDescriptor

import scala.collection.JavaConversions._

class DescribeCommand(parent: JCommander) extends Command with Logging {

  val params = new FeatureParams
  parent.addCommand(Command, params)

  def execute() = {
    println(s"Describing attributes of feature '${params.featureName}' from catalog table '${params.catalog}'...")
    try {
      val ds = new DataStoreHelper(params).ds
      val sft = ds.getSchema(params.featureName)

      def isIndexed(attr: AttributeDescriptor) =
        attr.getUserData.getOrElse("index", false).asInstanceOf[java.lang.Boolean]

      val sb = new StringBuilder()
      sft.getAttributeDescriptors.foreach { attr =>
        sb.clear()
        val name = attr.getLocalName

        // TypeName
        sb.append(name)
        sb.append(": ")
        sb.append(attr.getType.getBinding.getSimpleName)

        if (extractDtgField(sft) == name)      sb.append(" (Time-index)")
        if (sft.getGeometryDescriptor == attr) sb.append(" (Geo-index)")
        if (isIndexed(attr))                   sb.append(" (Indexed)")
        if (attr.getDefaultValue != null)      sb.append("- Default Value: ", attr.getDefaultValue)

        println(sb.toString())
      }
    } catch {
      case npe: NullPointerException =>
        logger.error("Error: feature not found. Check arguments...", npe)
      case e: Exception =>
        logger.error(s"Error describing feature '${params.featureName}': " + e.getMessage, e)
    }
  }

}

object DescribeCommand {
  val Command = "describe"
}

