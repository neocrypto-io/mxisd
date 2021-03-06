/*
 * mxisd - Matrix Identity Server Daemon
 * Copyright (C) 2017 Maxime Dor
 *
 * https://max.kamax.io/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.kamax.mxisd.controller.identity.v1;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.kamax.matrix.crypto.SignatureManager;
import io.kamax.matrix.event.EventKey;
import io.kamax.matrix.json.MatrixJson;
import io.kamax.mxisd.config.MatrixConfig;
import io.kamax.mxisd.controller.identity.v1.io.SingeLookupReplyJson;
import io.kamax.mxisd.exception.InternalServerError;
import io.kamax.mxisd.lookup.*;
import io.kamax.mxisd.lookup.strategy.LookupStrategy;
import io.kamax.mxisd.util.GsonParser;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@RestController
@CrossOrigin
@RequestMapping(path = IdentityAPIv1.BASE, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
public class MappingController {

    private Logger log = LoggerFactory.getLogger(MappingController.class);
    private Gson gson = new Gson();
    private GsonParser parser = new GsonParser(gson);

    @Autowired
    private MatrixConfig mxCfg;

    @Autowired
    private LookupStrategy strategy;

    @Autowired
    private SignatureManager signMgr;

    private void setRequesterInfo(ALookupRequest lookupReq, HttpServletRequest req) {
        lookupReq.setRequester(req.getRemoteAddr());
        String xff = req.getHeader("X-FORWARDED-FOR");
        log.debug("XFF header: {}", xff);
        lookupReq.setRecursive(StringUtils.isBlank(xff));
        if (!lookupReq.isRecursive()) {
            lookupReq.setRecurseHosts(Arrays.asList(xff.split(",")));
            lookupReq.setRequester(lookupReq.getRecurseHosts().get(lookupReq.getRecurseHosts().size() - 1));
        }

        lookupReq.setUserAgent(req.getHeader("USER-AGENT"));
    }

    @RequestMapping(value = "/lookup", method = GET)
    String lookup(HttpServletRequest request, @RequestParam String medium, @RequestParam String address) {
        SingleLookupRequest lookupRequest = new SingleLookupRequest();
        setRequesterInfo(lookupRequest, request);
        lookupRequest.setType(medium);
        lookupRequest.setThreePid(address);

        log.info("Got single lookup request from {} with client {} - Is recursive? {}", lookupRequest.getRequester(), lookupRequest.getUserAgent(), lookupRequest.isRecursive());

        Optional<SingleLookupReply> lookupOpt = strategy.find(lookupRequest);
        if (!lookupOpt.isPresent()) {
            log.info("No mapping was found, return empty JSON object");
            return "{}";
        }

        SingleLookupReply lookup = lookupOpt.get();

        // FIXME signing should be done in the business model, not in the controller
        JsonObject obj = gson.toJsonTree(new SingeLookupReplyJson(lookup)).getAsJsonObject();
        obj.add(EventKey.Signatures.get(), signMgr.signMessageGson(MatrixJson.encodeCanonical(obj)));

        return gson.toJson(obj);
    }

    @RequestMapping(value = "/bulk_lookup", method = POST)
    String bulkLookup(HttpServletRequest request) {
        BulkLookupRequest lookupRequest = new BulkLookupRequest();
        setRequesterInfo(lookupRequest, request);
        log.info("Got bulk lookup request from {} with client {} - Is recursive? {}", lookupRequest.getRequester(), lookupRequest.getUserAgent(), lookupRequest.isRecursive());

        try {
            ClientBulkLookupRequest input = parser.parse(request, ClientBulkLookupRequest.class);
            List<ThreePidMapping> mappings = new ArrayList<>();
            for (List<String> mappingRaw : input.getThreepids()) {
                ThreePidMapping mapping = new ThreePidMapping();
                mapping.setMedium(mappingRaw.get(0));
                mapping.setValue(mappingRaw.get(1));
                mappings.add(mapping);
            }
            lookupRequest.setMappings(mappings);

            ClientBulkLookupAnswer answer = new ClientBulkLookupAnswer();
            answer.addAll(strategy.find(lookupRequest));
            return gson.toJson(answer);
        } catch (IOException e) {
            throw new InternalServerError(e);
        }
    }

}
