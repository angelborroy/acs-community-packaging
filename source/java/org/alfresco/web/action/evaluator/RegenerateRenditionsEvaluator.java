/*
 * Copyright (C) 2005-2007 Alfresco Software Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 * As a special exception to the terms and conditions of version 2.0 of
 * the GPL, you may redistribute this Program in connection with Free/Libre
 * and Open Source Software ("FLOSS") applications as described in Alfresco's
 * FLOSS exception.  You should have recieved a copy of the text describing
 * the FLOSS exception, and it is also available here:
 * http://www.alfresco.com/legal/licensing" 
 */
package org.alfresco.web.action.evaluator;

import javax.faces.context.FacesContext;
import org.alfresco.model.WCMAppModel;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.Path;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.web.action.ActionEvaluator;
import org.alfresco.web.app.Application;
import org.alfresco.web.app.servlet.FacesHelper;
import org.alfresco.web.bean.NavigationBean;
import org.alfresco.web.bean.repository.Node;
import org.alfresco.web.bean.repository.Repository;

/**
 * UI Action Evaluator for Regenerate Renditions in the Forms DataDictionary folder
 * 
 * @author Ariel Backenroth
 */
public class RegenerateRenditionsEvaluator implements ActionEvaluator
{
   /**
    * @see org.alfresco.web.action.ActionEvaluator#evaluate(org.alfresco.web.bean.repository.Node)
    */
   public boolean evaluate(final Node node)
   {
      final FacesContext fc = FacesContext.getCurrentInstance();
      final ServiceRegistry services = Repository.getServiceRegistry(fc);
      final NavigationBean navigator = (NavigationBean)FacesHelper.getManagedBean(fc, NavigationBean.BEAN_NAME);
      
      // get the path to the current name - compare last element with the Website folder assoc name
      final Path path = navigator.getCurrentNode().getNodePath();
      final Path.Element element = path.get(path.size() - 1);
      final String endPath = element.getPrefixedString(services.getNamespaceService());

      return (node.hasAspect(WCMAppModel.ASPECT_FORM) ||
              node.hasAspect(WCMAppModel.ASPECT_RENDERING_ENGINE_TEMPLATE) || 
              Application.getContentFormsFolderName(fc).equals(endPath));
   }
}