/*
 * Copyright (C) 2005 Alfresco, Inc.
 *
 * Licensed under the Mozilla Public License version 1.1 
 * with a permitted attribution clause. You may obtain a
 * copy of the License at
 *
 *   http://www.alfresco.org/legal/license.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.alfresco.web.forms;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.faces.context.FacesContext;
import org.alfresco.model.ContentModel;
import org.alfresco.model.WCMAppModel;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.repo.avm.AVMNodeConverter;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.MimetypeService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.TemplateNode;
import org.alfresco.service.cmr.repository.TemplateService;
import org.alfresco.service.namespace.QName;
import org.alfresco.web.bean.repository.Repository;
import org.alfresco.web.bean.wcm.AVMConstants;
import org.alfresco.web.ui.common.Utils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Encapsulation of a rendition.
 *
 * @author Ariel Backenroth
 */
public class RenditionImpl
   implements Rendition
{

   private final NodeRef nodeRef;

   public RenditionImpl(final NodeRef nodeRef)
   {
      this.nodeRef = nodeRef;
   }

   /** the name of this rendition */
   public String getName()
   {
      final NodeService nodeService = this.getServiceRegistry().getNodeService();
      return (String)
         nodeService.getProperty(this.nodeRef, ContentModel.PROP_NAME);
   }

   /** the path relative to the containing webapp */
   public String getWebappRelativePath()
   {
      final String path = AVMNodeConverter.ToAVMVersionPath(this.nodeRef).getSecond();
      final String p = ("[^:]+:/" + AVMConstants.DIR_APPBASE +
                        "/" + AVMConstants.DIR_WEBAPPS +
                        "/[^/]+(.*)/" + this.getName());
      final Matcher m = Pattern.compile(p).matcher(path);
      return m.matches() && m.group(1).length() != 0 ? m.group(1) : "/";
   }

   public FormInstanceData getPrimaryFormInstanceData()
   {
      final NodeService nodeService = this.getServiceRegistry().getNodeService();
      final NodeRef fidNodeRef = (NodeRef)
         nodeService.getProperty(this.nodeRef, 
                                 WCMAppModel.PROP_PRIMARY_FORM_INSTANCE_DATA);
      return new FormInstanceDataImpl(fidNodeRef);
   }

   /** the rendering engine template that generated this rendition */
   public RenderingEngineTemplate getRenderingEngineTemplate()
   {
      final NodeService nodeService = this.getServiceRegistry().getNodeService();
      final NodeRef retNodeRef = (NodeRef)
         nodeService.getProperty(this.nodeRef, 
                                 WCMAppModel.PROP_PARENT_RENDERING_ENGINE_TEMPLATE);
      final NodeRef rpNodeRef = (NodeRef)
         nodeService.getProperty(this.nodeRef, 
                                 WCMAppModel.PROP_PARENT_RENDITION_PROPERTIES);
      return new RenderingEngineTemplateImpl(retNodeRef, rpNodeRef);
   }

   /** the node ref containing the contents of this rendition */
   public NodeRef getNodeRef()
   {
      return this.nodeRef;
   }

   public String getUrl()
   {
      return AVMConstants.buildAVMAssetUrl(AVMNodeConverter.ToAVMVersionPath(this.nodeRef).getSecond());
   }

   public String getFileTypeImage()
   {
      return Utils.getFileTypeImage(this.getName(), false);
   }

   private ServiceRegistry getServiceRegistry()
   {
      final FacesContext fc = FacesContext.getCurrentInstance();
      return Repository.getServiceRegistry(fc);
   }
}