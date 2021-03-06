/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.fediz.service.oidc.logout;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.apache.cxf.fediz.service.oidc.FedizSubjectCreator;
import org.apache.cxf.jaxrs.ext.MessageContext;
import org.apache.cxf.rs.security.oauth2.common.Client;
import org.apache.cxf.rs.security.oauth2.common.UserSubject;
import org.apache.cxf.rs.security.oauth2.provider.OAuthDataProvider;
import org.apache.cxf.rs.security.oauth2.utils.OAuthConstants;

@Path("/logout")
public class LogoutService {
    private static final String CLIENT_LOGOUT_URI = "post_logout_redirect_uri";
    private static final String CLIENT_LOGOUT_URIS = "post_logout_redirect_uris";
    @Context
    private MessageContext mc;
    private String relativeIdpLogoutUri;
    private OAuthDataProvider dataProvider;
    private FedizSubjectCreator subjectCreator = new FedizSubjectCreator();

    private List<LogoutHandler> logoutHandlers;

    @POST
    public Response initiateLogoutPost(MultivaluedMap<String, String> params) {
        return doInitiateLogout(params);
    }
    @GET
    public Response initiateLogoutGet() {
        return doInitiateLogout(mc.getUriInfo().getQueryParameters());
    }

    protected Response doInitiateLogout(MultivaluedMap<String, String> params) {
        Client client = getClient(params);
        UserSubject subject = subjectCreator.createUserSubject(mc, params);

        if (logoutHandlers != null) {

            for (LogoutHandler handler : logoutHandlers) {
                handler.handleLogout(client, subject);
            }
        }
        // Clear OIDC session now
        mc.getHttpServletRequest().getSession().invalidate();

        // Redirect to the core IDP
        URI idpLogoutUri = getAbsoluteIdpLogoutUri(client, params);
        return Response.seeOther(idpLogoutUri).build();
    }

    private URI getClientLogoutUri(Client client, MultivaluedMap<String, String> params) {
        String logoutUriProp = client.getProperties().get(CLIENT_LOGOUT_URIS);
        // logoutUriProp is guaranteed to be not null at this point
        String[] uris = logoutUriProp.split(" ");
        String uriStr = null;
        if (uris.length > 1) {
            String clientLogoutUriParam = params.getFirst(CLIENT_LOGOUT_URI);
            if (clientLogoutUriParam == null 
                    || !new HashSet<>(Arrays.asList(uris)).contains(clientLogoutUriParam)) {
                throw new BadRequestException();    
            }
            uriStr = clientLogoutUriParam;
        } else {
            uriStr = uris[0];
        }
        UriBuilder ub = UriBuilder.fromUri(uriStr);
        String state = params.getFirst(OAuthConstants.STATE);
        if (state != null) {
            ub.queryParam(OAuthConstants.STATE, state);
        }
        return ub.build();
    }
    
    private Client getClient(MultivaluedMap<String, String> params) {
        String clientId = params.getFirst(OAuthConstants.CLIENT_ID);
        if (clientId == null) {
            throw new BadRequestException();
        }
        Client c = dataProvider.getClient(clientId);
        if (c == null) {
            throw new BadRequestException();
        }
        if (c.getProperties().get(CLIENT_LOGOUT_URIS) == null) {
            //TODO: Possibly default to something ?
            throw new BadRequestException();
        }
        return c;
    }
    private URI getAbsoluteIdpLogoutUri(Client client, MultivaluedMap<String, String> params) {
        UriBuilder ub = mc.getUriInfo().getAbsolutePathBuilder();
        ub.path(relativeIdpLogoutUri);
        ub.queryParam("wreply", getClientLogoutUri(client, params));
        ub.queryParam(OAuthConstants.CLIENT_ID, client.getClientId());
        return ub.build();
    }

    public void setRelativeIdpLogoutUri(String relativeIdpLogoutUri) {
        this.relativeIdpLogoutUri = relativeIdpLogoutUri;
    }

    public void setLogoutHandlers(List<LogoutHandler> logoutHandlers) {
        this.logoutHandlers = logoutHandlers;
    }
    public void setLogoutHandler(LogoutHandler logoutHandler) {
        setLogoutHandlers(Collections.singletonList(logoutHandler));
    }
    public void setDataProvider(OAuthDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }
    public void setSubjectCreator(FedizSubjectCreator subjectCreator) {
        this.subjectCreator = subjectCreator;
    }
}
