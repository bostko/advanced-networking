/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.networking.vclouddirector.natmicroservice;

import java.lang.reflect.Type;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;

@Provider
public class NullHttpServletRequestProvider implements InjectableProvider<Context, Type> { 
    public Injectable<HttpServletRequest> getInjectable(ComponentContext ic, 
            Context a, Type c) { 
        if (HttpServletRequest.class == c) { 
            return new Injectable<HttpServletRequest>() {
                public HttpServletRequest getValue() { return null; }
            }; 
        } else 
            return null; 
    } 
    public ComponentScope getScope() { 
        return ComponentScope.Singleton; 
    } 
} 
