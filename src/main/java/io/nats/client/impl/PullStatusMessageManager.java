// Copyright 2021 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client.impl;

import io.nats.client.JetStreamStatusException;
import io.nats.client.Message;

import java.util.Arrays;
import java.util.List;

class PullStatusMessageManager extends MessageManager {

    private static final List<Integer> PULL_KNOWN_STATUS_CODES = Arrays.asList(404, 408);

    private int lastStatusCode = -1;

    boolean manage(Message msg) {
        if (msg.isStatusMessage()) {
            lastStatusCode = msg.getStatus().getCode();
            if ( !PULL_KNOWN_STATUS_CODES.contains(lastStatusCode) ) {
                throw new JetStreamStatusException(sub, msg.getStatus());
            }
            return true;
        }

        lastStatusCode = -1;
        return false;
    }

    public int getLastStatusCode() {
        return lastStatusCode;
    }
}
