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
package org.alfresco.web.bean.wcm;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.faces.context.FacesContext;

import org.alfresco.model.WCMAppModel;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.security.AuthorityType;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.namespace.RegexQNamePattern;
import org.alfresco.web.app.Application;
import org.alfresco.web.bean.repository.Node;
import org.alfresco.web.bean.wizard.InviteUsersWizard;

/**
 * Bean providing the ability to invite users to a web project space.
 * 
 * @author kevinr
 */
public class InviteWebsiteUsersWizard extends InviteUsersWizard
{
   /** Cache of available folder permissions */
   Set<String> folderPermissions = null;
   
   /** the node representing the website */
   private Node website;
   
   /** list of authorities with the Content Manager role */
   private List<String> managers;
   
   /** root AVM store the users are invited into */
   private String avmStore;
   
   /** assume we are launching the wizard standalone */
   private boolean standalone = true;
   
   /** AVM Browse Bean reference */
   protected AVMBrowseBean avmBrowseBean;
   
   
   /**
    * @param avmBrowseBean    The AVMBrowseBean to set.
    */
   public void setAvmBrowseBean(AVMBrowseBean avmBrowseBean)
   {
      this.avmBrowseBean = avmBrowseBean;
   }
   
   /**
    * @see org.alfresco.web.bean.wizard.InviteUsersWizard#init(java.util.Map)
    */
   @Override
   public void init(Map<String, String> parameters)
   {
      super.init(parameters);
      // only allow one selection per authority
      this.allowDuplicateAuthorities = false;
      this.website = null;
      this.managers = null;
      this.avmStore = null;
      this.standalone = true;
   }

   /**
    * @see org.alfresco.web.bean.wizard.InviteUsersWizard#finishImpl(javax.faces.context.FacesContext, java.lang.String)
    */
   @Override
   protected String finishImpl(FacesContext context, String outcome) throws Exception
   {
      super.finishImpl(context, outcome);
      
      // create a sandbox for each user appropriately with permissions based on role
      // build a list of managers who will have full permissions on ALL staging areas
      this.managers = new ArrayList<String>(4);
      Set<String> excludeUsers = new HashSet(4);
      if (isStandalone() == false)
      {
         // no website created yet - so we need to build the list of managers from the
         // invited users and the power user who is executing the create web project wizard 
         boolean foundCurrentUser = false;
         String currentUser = Application.getCurrentUser(context).getUserName();
         
         for (UserGroupRole userRole : this.userGroupRoles)
         {
            for (String userAuth : findNestedUserAuthorities(userRole.getAuthority()))
            {
               if (currentUser.equals(userAuth))
               {
                  foundCurrentUser = true;
               }
               if (AVMConstants.ROLE_CONTENT_MANAGER.equals(userRole.getRole()))
               {
                  this.managers.add(userAuth);
               }
            }
         }
         
         if (foundCurrentUser == false)
         {
            this.userGroupRoles.add(new UserGroupRole(currentUser, AVMConstants.ROLE_CONTENT_MANAGER, null));
            this.managers.add(currentUser);
         }
      }
      else
      {
         // website already exists - we are only adding to the existing sandboxes
         // so retrieve the list of managers from the existing users and the selected invitees
         for (UserGroupRole userRole : this.userGroupRoles)
         {
            for (String userAuth : findNestedUserAuthorities(userRole.getAuthority()))
            {
               if (AVMConstants.ROLE_CONTENT_MANAGER.equals(userRole.getRole()))
               {
                  this.managers.add(userAuth);
               }
            }
         }
         
         List<ChildAssociationRef> userInfoRefs = this.nodeService.getChildAssocs(
            getNode().getNodeRef(), WCMAppModel.ASSOC_WEBUSER, RegexQNamePattern.MATCH_ALL);
         for (ChildAssociationRef ref : userInfoRefs)
         {
            NodeRef userInfoRef = ref.getChildRef();
            String username = (String)nodeService.getProperty(userInfoRef, WCMAppModel.PROP_WEBUSERNAME);
            String userrole = (String)nodeService.getProperty(userInfoRef, WCMAppModel.PROP_WEBUSERROLE);
            
            if (AVMConstants.ROLE_CONTENT_MANAGER.equals(userrole) &&
                this.managers.contains(username) == false)
            {
               this.managers.add(username);
            }
            
            // add each existing user to the exclude this - we cannot add them more than once!
            excludeUsers.add(username);
         }
      }
      
      // build the sandboxes now we have the manager list and complete user list
      // and create an association to a node to represent each invited user
      List<SandboxInfo> sandboxInfoList = new LinkedList<SandboxInfo>();
      for (UserGroupRole userRole : this.userGroupRoles)
      {
         for (String userAuth : findNestedUserAuthorities(userRole.getAuthority()))
         {
            if (excludeUsers.contains(userAuth) == false)
            {
               SandboxInfo info = SandboxFactory.createUserSandbox(
                     getAvmStore(), this.managers, userAuth, userRole.getRole());
               
               sandboxInfoList.add(info);
               
               // create an app:webuser instance for each authority and assoc to the website node
               Map<QName, Serializable> props = new HashMap<QName, Serializable>(2, 1.0f);
               props.put(WCMAppModel.PROP_WEBUSERNAME, userAuth);
               props.put(WCMAppModel.PROP_WEBUSERROLE, userRole.getRole());
               this.nodeService.createNode(getNode().getNodeRef(),
                     WCMAppModel.ASSOC_WEBUSER,
                     WCMAppModel.ASSOC_WEBUSER,
                     WCMAppModel.TYPE_WEBUSER,
                     props);
            }
         }
      }
      
      // reload virtualisation server for webapp in this web project
      if (isStandalone())
      {
         for (SandboxInfo sandboxInfo : sandboxInfoList)
         {
             String newlyInvitedStoreName = AVMConstants.buildStagingStoreName(sandboxInfo.getMainStoreName());
             String path = AVMConstants.buildStoreWebappPath(newlyInvitedStoreName, this.avmBrowseBean.getWebapp());
             AVMConstants.updateVServerWebapp(path, true);
         }
      }
      
      return outcome;
   }

   /**
    * Find all nested user authorities contained with an authority
    * 
    * @param authority     The authority to search, USER authorities are returned immediately, GROUP authorites
    *                      are recursively scanned for contained USER authorities.
    * 
    * @return a Set of USER authorities
    */
   private Set<String> findNestedUserAuthorities(String authority)
   {
      Set<String> users;
      
      AuthorityType authType = AuthorityType.getAuthorityType(authority);
      if (authType.equals(AuthorityType.USER))
      {
         users = new HashSet<String>(1, 1.0f);
         if (this.personService.personExists(authority) == true)
         {
            users.add(authority); 
         }
      }
      else if (authType.equals(AuthorityType.GROUP))
      {
         // walk each member of the group
         users = this.authorityService.getContainedAuthorities(AuthorityType.USER, authority, false);
         for (String userAuth : users)
         {
            if (this.personService.personExists(userAuth) == false)
            {
               users.remove(authType);
            }
         }
      }
      else
      {
         users = Collections.<String>emptySet();
      }
      
      return users;
   }
   
   /**
    * @return summary text for the wizard
    */
   public String getSummary()
   {
      FacesContext fc = FacesContext.getCurrentInstance();
      
      // build a summary section to list the invited users and there roles
      StringBuilder buf = new StringBuilder(128);
      String currentUser = Application.getCurrentUser(fc).getUserName();
      boolean foundCurrentUser = false;
      for (UserGroupRole userRole : this.userGroupRoles)
      {
         if (currentUser.equals(userRole.getAuthority()))
         {
            foundCurrentUser = true;
         }
         buf.append(userRole.getLabel());
         buf.append("<br>");
      }
      if (foundCurrentUser == false)
      {
         buf.append(buildLabelForUserAuthorityRole(
               currentUser, AVMConstants.ROLE_CONTENT_MANAGER));
      }
      
      return buildSummary(
            new String[] {Application.getMessage(fc, MSG_USERROLES)},
            new String[] {buf.toString()});
   }

   @Override
   protected Set<String> getPermissionsForType()
   {
      if (this.folderPermissions == null)
      {
         // get permissions and roles for a website folder type
         this.folderPermissions = this.permissionService.getSettablePermissions(WCMAppModel.TYPE_AVMWEBFOLDER);
      }
       
      return this.folderPermissions;
   }

   protected void setNode(Node node)
   {
      this.website = node;
   }
   
   @Override
   protected Node getNode()
   {
      if (this.website != null)
      {
         return this.website;
      }
      else
      {
         return this.browseBean.getActionSpace();
      }
   }

   /**
    * @return List of authorities with the Content Manager role
    */
   public List<String> getManagers()
   {
      return this.managers;
   }

   /**
    * @return Returns the root AVM store.
    */
   public String getAvmStore()
   {
      if (this.avmStore == null)
      {
         this.avmStore = (String)getNode().getProperties().get(WCMAppModel.PROP_AVMSTORE);
      }
      return this.avmStore;
   }

   /**
    * @param avmStore The root AVM store to set.
    */
   public void setAvmStore(String avmStore)
   {
      this.avmStore = avmStore;
   }

   /**
    * @return Returns the edit mode.
    */
   public boolean isStandalone()
   {
      return this.standalone;
   }

   /**
    * @param editMode The edit mode to set.
    */
   public void setStandalone(boolean editMode)
   {
      this.standalone = editMode;
   }
}
