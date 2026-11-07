# Licenses — Zenbon POS server package (internal release, with TSE runtime)

## Zenbon POS

The Zenbon POS software in this package (`pos-server.jar` and the scripts) is
licensed under the MIT License:

    Copyright (c) 2024 POS System

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

## Open-source components

The open-source libraries inside `pos-server.jar` and the Java runtime in `jre/`
(Eclipse Temurin, GPL-2.0 with Classpath Exception) are listed with their
licenses and license texts in `THIRD-PARTY-LICENSES.txt`, next to this file.

## Proprietary: Swissbit TSE SDK in `native/`

The `native/` folder contains the **Swissbit TSE SDK** (`WormAPI.jar` and its
native libraries). It is proprietary software of Swissbit AG and is **not**
covered by the MIT License or by any open-source license. Its use and any
redistribution are governed solely by Swissbit's license terms. It is included
in this internal package only; do not pass `native/` on to third parties unless
Swissbit's terms allow it.
