@file:OptIn(ExperimentalEncodingApi::class)

package org.example.project.ui.components.pdf

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The commonTest/resources/pdf fixtures (plus the image-decoder ones, generated with PDFBox outside the repo),
 * embedded as base64 because a wasm test must not depend on a resource file (resources do not propagate on wasm). Regenerate from those files if they change.
 */
internal object PdfFixtures {
    // two-page.pdf
    private const val TWO_PAGE_B64 =
        "JVBERi0xLjYKJfbk/N8KMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovVmVyc2lvbiAvMS42Ci9QYWdlcyAyIDAgUgo+PgplbmRv" +
            "YmoKNiAwIG9iago8PAovTGVuZ3RoIDU1Ci9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQ0KeJxzCuHSdzNUMDJRCEnjMjFQ" +
            "MLcwUghJ4dIISExPVTBUKMgvKilKzCzRVAjJ4nIN4QIACMEL9w0KZW5kc3RyZWFtCmVuZG9iago4IDAgb2JqCjw8Ci9MZW5ndGgg" +
            "NTYKL0ZpbHRlciAvRmxhdGVEZWNvZGUKPj4Kc3RyZWFtDQp4nHMK4dJ3M1QwMlEISeMyMVAwNTZVCEnh0ghITE9VMFLIScxLKU5O" +
            "LEjVVAjJ4nIN4QIAE4gMKg0KZW5kc3RyZWFtCmVuZG9iagoxMyAwIG9iago8PAovTGVuZ3RoIDIzMQovVHlwZSAvT2JqU3RtCi9O" +
            "IDkKL0ZpbHRlciAvRmxhdGVEZWNvZGUKL0ZpcnN0IDU0Cj4+CnN0cmVhbQ0KeJy1UNFKAzEQ/JX5AjfZJN4FSsEWRSgFqYIPpQ/n" +
            "XSgBSaS5K/XvTe5A2griiw8ZmNndmTAMAQVdQ0NqAwPWjApsLCy4qiAF2CpICSUsJENZgdmMXj4/HOip2bsEWvkuYauy1yYbFdyB" +
            "lnEIPRjz+eU+aO063yziCVtxI1CesSZjrTnjrqwd3HhbrGjjUhwObQ4yk7CMoc/zhNuR/yVgsp5ifgmorgLqs4CHLOZSroTcz5ki" +
            "S1GXnH/8cbyj5+GtH2kRJWjRJDdNHt370fW+bUD3oY2dD3vQqw93Iflv4T8MvwCJm4/FDQplbmRzdHJlYW0KZW5kb2JqCjE0IDAg" +
            "b2JqCjw8Ci9MZW5ndGggNDYKL1Jvb3QgMSAwIFIKL0lEIFs8MzE0NTI5RTIzQzVBOEYwRDVGOEY4ODgyMDQ2ODQ3REM2RDcxM0E4" +
            "RDMwRUJGQkY1NjcwRjlDMURDM0JDNEZDQT4gPDMxNDUyOUUyM0M1QThGMEQ1RjhGODg4MjA0Njg0N0RDNkQ3MTNBOEQzMEVCRkJG" +
            "NTY3MEY5QzFEQzNCQzRGQ0E+XQovVHlwZSAvWFJlZgovU2l6ZSAxNQovSW5kZXggWzAgMTRdCi9XIFsxIDIgMV0KL0ZpbHRlciAv" +
            "RmxhdGVEZWNvZGUKPj4Kc3RyZWFtDQp4nBXHuQ0AIBDEQC9/gugMOqcl2AtGloEnFokZZMmy2PFF3Gi1Zt2GdPhj+AMqDQplbmRz" +
            "dHJlYW0KZW5kb2JqCnN0YXJ0eHJlZgo2NzAKJSVFT0YK"

    // corrupt.pdf
    private const val CORRUPT_B64 =
        "JVBERi0xLjYKJfbk/N8KMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovVmVyc2lvbiAvMS42Ci9QYWdlcyAyIDAgUgo+PgplbmRv" +
            "YmoKNiAwIG9iago8PAovTGVuZ3RoIDU1Ci9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQ0KeJxzCuHSdzNUMDJRCEnjMjFQ" +
            "MLcwUghJ4dIISExPVTBUKMgvKilKzCzRVAjJ4nIN4QIACMEL9w0KZW5kc3RyZWFtCmVuZG9iago4IDAgb2JqCjw8Ci9MZW5ndGgg" +
            "NTYKL0ZpbHRlciAvRmxhdGVEZWNvZGUKPj4Kc3RyZWFtDQp4nHMK4dJ3M1QwMlEISeMyMVAwNTZVCEnh0ghITE9VMFLIScxLKU5O" +
            "LEjVVAjJ4nIN4QIAE4gMKg0KZW5kc3RyZWFtCmVuZG9iagoxMyAwIG9iago8PAovTGVuZ3RoIDIzMQovVHlwZSAvT2JqU3RtCi9O" +
            "IDkKL0ZpbHRlciAvRmxhdGVEZWNvZGUKL0ZpcnN0IDU0Cj4+CnN0cmVhbQ0KeJy1UNFKAzEQ/JX5AjfZJN4FSsEWRSgFqYIPpQ/n" +
            "XSgBSaS5K/XvTe5A2griiw8ZmNndmTAMAQVdQ0NqAwPWjApsLCy4qiAF2CpICSUsJENZgdmM"

    // encrypted.pdf
    private const val ENCRYPTED_B64 =
        "JVBERi0xLjYKJfbk/N8KMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovVmVyc2lvbiAvMS42Ci9QYWdlcyAyIDAgUgo+PgplbmRv" +
            "YmoKNSAwIG9iago8PAovTGVuZ3RoIDQ2Ci9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQ0K+B14L521PAgOU3kJN7aN0VR4" +
            "hJSlH7K5Yk7vn39l1lNUpIyRfkRgYy0P/wTdMw0KZW5kc3RyZWFtCmVuZG9iago4IDAgb2JqCjw8Ci9MZW5ndGggMTg4Ci9UeXBl" +
            "IC9PYmpTdG0KL04gNQovRmlsdGVyIC9GbGF0ZURlY29kZQovRmlyc3QgMjcKPj4Kc3RyZWFtDQqdM3tJk9pobJemrbJuAFxW/PYQ" +
            "dDDFJOR/O9rPcRFqI81Mq1pa4Pgu01obIKeftjex7X/qvr4UeShsvhB/jRNytUEBX3K0f6w9stxbi4e7Ghvz0mL1pER8+xUV7GoN" +
            "Y5Y+TA6TVMEDodkb45tVMuLDFNwy5ChstkEKQi30/MHmGCLssRUClZcZ0GV85x7+gZ/qlhxIdLf6zjpGEjQruLCJ6pzIN9A09vBj" +
            "APEGjYO5bSw7vdWchkw3MEwDKg0KZW5kc3RyZWFtCmVuZG9iago5IDAgb2JqCjw8Ci9GaWx0ZXIgL1N0YW5kYXJkCi9WIDIKL1Ig" +
            "MwovTGVuZ3RoIDEyOAovUCAtNAovTyA8MEJBMzgzNUY4OEY5MDM4OEU3NEU1NDU4NDEyNUNFMTQyQkUwREUyNEM2QjBEMzc3NDZF" +
            "MDc1Qjg5MTc1NjY3MT4KL1UgPEE4NzI4RTZEOTFFNUJEN0Q1REIwNkFGNDQ4MDE2RDAzMjhCRjRFNUU0RTc1OEE0MTY0MDA0RTU2" +
            "RkZGQTAxMDg+Cj4+CmVuZG9iagoxMCAwIG9iago8PAovTGVuZ3RoIDQwCi9Sb290IDEgMCBSCi9JRCBbPEU5QUMzNDNGMDhEOTA3" +
            "RDA4QzJGQzYyM0JGMDlFODU2PiA8RTlBQzM0M0YwOEQ5MDdEMDhDMkZDNjIzQkYwOUU4NTY+XQovRW5jcnlwdCA5IDAgUgovVHlw" +
            "ZSAvWFJlZgovU2l6ZSAxMQovSW5kZXggWzAgMTBdCi9XIFsxIDIgMV0KL0ZpbHRlciAvRmxhdGVEZWNvZGUKPj4Kc3RyZWFtDQp4" +
            "nGNgYPjPyMDPwMTAAcKMQMzEyOAHYjMDMQsjw1EGRsYXDAA30ANLDQplbmRzdHJlYW0KZW5kb2JqCnN0YXJ0eHJlZgo2OTUKJSVF" +
            "T0YK"

    // zero-crop-box.pdf
    private const val ZERO_CROP_BOX_B64 =
        "JVBERi0xLjYKJfbk/N8KMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovVmVyc2lvbiAvMS42Ci9QYWdlcyAyIDAgUgo+PgplbmRv" +
            "YmoKNCAwIG9iago8PAovTGVuZ3RoIDEwNgovVHlwZSAvT2JqU3RtCi9OIDIKL0ZpbHRlciAvRmxhdGVEZWNvZGUKL0ZpcnN0IDkK" +
            "Pj4Kc3RyZWFtDQp4nDNSMFAwVjAxUrCx0Q+pLEhV0A9ITE8tVtD3zkwpVog2BkoHKcQq6Dvnl+aVKBgq2NmhqlTQ901NyUx0yq9Q" +
            "iDbQM1AAYVNLUyBpYWIEJEFai/ILUORhOBZkRFEq0FwjsDVAswFwziQzDQplbmRzdHJlYW0KZW5kb2JqCjUgMCBvYmoKPDwKL0xl" +
            "bmd0aCAyMQovUm9vdCAxIDAgUgovSUQgWzw5RURGNTI5MDg0MTcxNzE5ODE3N0RCRUI4MEYyREFFRENCMEJGRjY4RjBBQ0I2NDg3" +
            "QTM1RjMzNzFEQkI0QTg3PiA8OUVERjUyOTA4NDE3MTcxOTgxNzdEQkVCODBGMkRBRURDQjBCRkY2OEYwQUNCNjQ4N0EzNUYzMzcx" +
            "REJCNEE4Nz5dCi9UeXBlIC9YUmVmCi9TaXplIDYKL0luZGV4IFswIDVdCi9XIFsxIDEgMV0KL0ZpbHRlciAvRmxhdGVEZWNvZGUK" +
            "Pj4Kc3RyZWFtDQp4nGNg+M/Iz8DEAkSMjH4MAA6oAWwNCmVuZHN0cmVhbQplbmRvYmoKc3RhcnR4cmVmCjI4NgolJUVPRgo="

    // not-a-pdf.xml
    private const val NOT_A_PDF_B64 =
        "PD94bWwgdmVyc2lvbj0iMS4wIj8+PGludm9pY2U+PGlkPjE8L2lkPjwvaW52b2ljZT4K"

    // ccitt-g4.pdf: 200x100 pt page filled by a 1-bit CCITT G4 image (PDFBox CCITTFactory), white with a
    // black rectangle over x 50..149, y 25..74 — the compression bitonal office scanners use.
    private const val CCITT_G4_B64 =
        "JVBERi0xLjYKJfbk/N8KMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovVmVyc2lvbiAvMS42Ci9QYWdlcyAyIDAgUgo+PgplbmRv" +
            "YmoKNSAwIG9iago8PAovTGVuZ3RoIDMxCi9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQ0KeJwr5DIyMFAAQUMonZzLpe+Z" +
            "a6jgks8VyAUAYJoGRw0KZW5kc3RyZWFtCmVuZG9iago3IDAgb2JqCjw8Ci9MZW5ndGggMzMKL1R5cGUgL1hPYmplY3QKL1N1YnR5" +
            "cGUgL0ltYWdlCi9GaWx0ZXIgL0NDSVRURmF4RGVjb2RlCi9CaXRzUGVyQ29tcG9uZW50IDEKL1dpZHRoIDIwMAovSGVpZ2h0IDEw" +
            "MAovQ29sb3JTcGFjZSAvRGV2aWNlR3JheQovRGVjb2RlUGFybXMgOCAwIFIKPj4Kc3RyZWFtDQr///+VMDw1P///////////////" +
            "/////////H///+ACACANCmVuZHN0cmVhbQplbmRvYmoKOSAwIG9iago8PAovTGVuZ3RoIDE2OQovVHlwZSAvT2JqU3RtCi9OIDUK" +
            "L0ZpbHRlciAvRmxhdGVEZWNvZGUKL0ZpcnN0IDI3Cj4+CnN0cmVhbQ0KeJxVjk8LgkAQxb/K+wK1/zQTxEOeIqKQDoF4MF3CSDdc" +
            "pfr2za6H6jADb2be/J4Eh0IgEUCoGCuIMMYaIgqRJOz0fmiwY3XVFmzXNhaFIkOOEiwzUz9CIE3/L8H2ummrjXmh4EsOV5K7Lnwv" +
            "3dmgySv9K5Zra6ahJkQwDzLTj7S3CL2eAefD5abrkRJ+Z9tOIPrRmblPXW8djt6ap3VICo6Fj/kBbGk7zg0KZW5kc3RyZWFtCmVu" +
            "ZG9iagoxMCAwIG9iago8PAovTGVuZ3RoIDM5Ci9Sb290IDEgMCBSCi9JRCBbPDJCMDc4Q0YxRTA3QzlFMjVDQjRBNERFMUUwMDc0" +
            "OTBDNkE2NDRCMTgzM0I2NkY1OERFREMyNTFFNDc1NUM0RUM+IDwyQjA3OENGMUUwN0M5RTI1Q0I0QTRERTFFMDA3NDkwQzZBNjQ0" +
            "QjE4MzNCNjZGNThERURDMjUxRTQ3NTVDNEVDPl0KL1R5cGUgL1hSZWYKL1NpemUgMTEKL0luZGV4IFswIDEwXQovVyBbMSAyIDFd" +
            "Ci9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQ0KeJxjYGD4z8jAz8DEwAnCjEDMxMjgB2IzMzJsA9EsjIwzGAA53ALxDQpl" +
            "bmRzdHJlYW0KZW5kb2JqCnN0YXJ0eHJlZgo2ODAKJSVFT0YK"

    // jpx.pdf: 200x100 pt page filled by a lossless JPEG 2000 (JPXDecode) RGB image, white with a red rectangle
    // over x 50..149, y 25..74.
    private const val JPX_B64 =
        "JVBERi0xLjYKJfbk/N8KMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovVmVyc2lvbiAvMS42Ci9QYWdlcyAyIDAgUgo+PgplbmRv" +
            "YmoKNSAwIG9iago8PAovTGVuZ3RoIDMxCi9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQ0KeJwr5DIyMFAAQUMonZzLpe+Z" +
            "a6jgks8VyAUAYJoGRw0KZW5kc3RyZWFtCmVuZG9iago3IDAgb2JqCjw8Ci9MZW5ndGggMTU2MgovVHlwZSAvWE9iamVjdAovU3Vi" +
            "dHlwZSAvSW1hZ2UKL0ZpbHRlciAvSlBYRGVjb2RlCi9CaXRzUGVyQ29tcG9uZW50IDgKL1dpZHRoIDIwMAovSGVpZ2h0IDEwMAov" +
            "Q29sb3JTcGFjZSAvRGV2aWNlUkdCCj4+CnN0cmVhbQ0KAAAADGpQICANCocKAAAAFGZ0eXBqcDIgAAAAAGpwMiAAAAAtanAyaAAA" +
            "ABZpaGRyAAAAZAAAAMgAAwcHAAAAAAAPY29scgEAAAAAABAAAAXNanAyY/9P/1EALwAAAAAAyAAAAGQAAAAAAAAAAAAAAMgAAABk" +
            "AAAAAAAAAAAAAwcBAQcBAQcBAf9SAAwAAAABAAUEBAAB/1wAE0BASEhQSEhQSEhQSEhQSEhQ/2QAJQABQ3JlYXRlZCBieSBPcGVu" +
            "SlBFRyB2ZXJzaW9uIDIuNS4w/5AACgAAAAAFRgAB/5PPtBAUAFyU34EIFDupZB8XQ2ITH1ln1lSwmDjlKCzLDyPbK2Jfd2MVDQJP" +
            "34EIFDupZB8XQ2ITH1ln1lSwmDjlKCzLDyPbK2Jfd2MVDQJPgM/AWn4CkPtEACgS4LGaxWUTkcRL9kp8qk1huwFBQp8VleeVk6HJ" +
            "nl9oK8qUD3zI3A0ELSJdym5p4cfD04NxaCRGdO/PwFp+ApD7RAAoEuCxmsVlE5HES/ZKfKpNYbsBQUKfFZXnlZOhyZ5faCvKlA98" +
            "yNwNBC0iXcpuaeHHw9ODcWgkRnTvgM/Aen4FMPtGQCKw4qBjP+3ZvamCIuqbxxeDYp1APrxU0X+oVHlNJ39uJBivFhb5AYZBfvsy" +
            "X5yE/Y3aBGFokexDCjY4uuz1XFY5sw5UbCsXcGRSrXoVuz4z9TA91vv8gFJ98l/x0gdWfM/Aen4FMPtGQCKw4qBjP+3ZvamCIuqb" +
            "xxeDYp1APrxU0X+oVHlNJ39uJBivFhb5AYZBfvsyX5yE/Y3aBGFokexDCjY4uuz1XFY5sw5UbCsXcGRSrXoVuz4z9TA91vv8gFJ9" +
            "8l/x0gdWfIDPwKJ+BvD7RkCLNY8tn4S+N/bcYR4sDp0SeeBHNKBR8xDGmjivRWcAb/Kkql8EzoC/OUGcQawJ8NyjFmJjdrY4g5dh" +
            "p9wF2Xcso8pxesz5+QM83S/cfTRMLlSBUCoAgyhMjUgCNZyQqzQ0RuMIH+GSODhkFkz35/30wMlrBY/Q63/PwKJ+BvD7RkCLNY8t" +
            "n4S+N/bcYR4sDp0SeeBHNKBR8xDGmjivRWcAb/Kkql8EzoC/OUGcQawJ8NyjFmJjdrY4g5dhp9wF2Xcso8pxesz5+QM83S/cfTRM" +
            "LlSBUCoAgyhMjUgCNZyQqzQ0RuMIH+GSODhkFkz35/30wMlrBY/Q63+Az8DWfgpQfUKgwdEcI2B1UfXfvH5p8y0KN6fj8Ez+Sjnq" +
            "WE4NJka6E0rI7m1J7NXRhYi3WxGNtLLWylXefH+twESrnJu8KPbWWXbwBhHpTh4ap9WB+PJYwEZQAVmmIQSvwWImPZGGSoAAUQEA" +
            "AAAAAcukeVqAAAAISljgAAABCrIx+GUMygAAAAWoIAAAAACfrLDfnqjmVBzll56Ta8RgTzTghxqlz8DWfgpQfUKgwdEcI2B1UfXf" +
            "vH5p8y0KN6fj8Ez+SjnqWE4NJka6E0rI7m1J7NXRhYi3WxGNtLLWylXefH+twESrnJu8KPbWWXbwBhHpTh4ap9WB+PJYwEZQAVmm" +
            "IQSvwWImPZGGSoAAUQEAAAAAAcukeVqAAAAISljgAAABCrIx+GUMygAAAAWoIAAAAACfrLDfnqjmVBzll56Ta8RgTzTghxqlgOfw" +
            "D/4CefwPf4EOH7Qf9oQA4P90BaVU+XNS/Gq2NC+/yen1hvi6DnNSmkgt+efjMCsV5/LMmhO/WL6cMdWHoAwkHlV8NqMAMJAZS2Eg" +
            "l/MakYSBemBhINlxVhIF6YwkG4pNhIF6YwkGgARhIF6YwkHJ/yhotwV+ffLqdfpQiOBbO4Dl4tM7lp4ynDwzKimasp/hBosY3ugn" +
            "6KpbSbeYMh/n8A/+Ann8D3+BDh+0H/aEAOD/dAWlVPlzUvxqtjQvv8np9Yb4ug5zUppILfnn4zArFefyzJoTv1i+nDHVh6AMJB5V" +
            "fDajADCQGUthIJfzGpGEgXpgYSDZcVYSBemMJBuKTYSBemMJBoAEYSBemMJByf8oaLcFfn3y6nX6UIjgWzuA5eLTO5aeMpw8Myop" +
            "mrKf4QaLGN7oJ+iqW0m3mDIf/9kNCmVuZHN0cmVhbQplbmRvYmoKOCAwIG9iago8PAovTGVuZ3RoIDE0NgovVHlwZSAvT2JqU3Rt" +
            "Ci9OIDQKL0ZpbHRlciAvRmxhdGVEZWNvZGUKL0ZpcnN0IDIxCj4+CnN0cmVhbQ0KeJxVTkEKwjAQ/Mq8wE3SVCmUHuxJRJTiQSg9" +
            "1HaRCjbSpKC/N0kR8bADMzs7swoCCbSChkwyrCHTDHlO5/eTQaf2xha0H3qLOvHWCg2oNPPoIFEU/07Qgfuh3ZoXarESCKNEQBmx" +
            "CbaJ/a2KUVSxNfPU+Qq9CKUZnd9bpJEvBZfj9c6d87/9tN1DYvPlHyMcMgANCmVuZHN0cmVhbQplbmRvYmoKOSAwIG9iago8PAov" +
            "TGVuZ3RoIDM4Ci9Sb290IDEgMCBSCi9JRCBbPDJBNzAzQzlFNDAxRkU4NTRENDQyNjdFOTFGRDQ3ODkyMTgzQzg4OTRFNkJGRTQw" +
            "QzU4MjA3RUQ1OTNDRTQzNUE+IDwyQTcwM0M5RTQwMUZFODU0RDQ0MjY3RTkxRkQ0Nzg5MjE4M0M4ODk0RTZCRkU0MEM1ODIwN0VE" +
            "NTkzQ0U0MzVBPl0KL1R5cGUgL1hSZWYKL1NpemUgMTAKL0luZGV4IFswIDldCi9XIFsxIDIgMV0KL0ZpbHRlciAvRmxhdGVEZWNv" +
            "ZGUKPj4Kc3RyZWFtDQp4nGNgYPjPyMDPwMTAAcKMQMzEyOAHYjMzMmxjYGSvYgAAL+wCxg0KZW5kc3RyZWFtCmVuZG9iagpzdGFy" +
            "dHhyZWYKMjE2MwolJUVPRgo="

    val twoPage: ByteArray get() = decode(TWO_PAGE_B64)
    val corrupt: ByteArray get() = decode(CORRUPT_B64)
    val encrypted: ByteArray get() = decode(ENCRYPTED_B64)
    val zeroCropBox: ByteArray get() = decode(ZERO_CROP_BOX_B64)
    val notAPdf: ByteArray get() = decode(NOT_A_PDF_B64)
    val ccittG4: ByteArray get() = decode(CCITT_G4_B64)
    val jpx: ByteArray get() = decode(JPX_B64)

    private fun decode(base64: String): ByteArray = Base64.decode(base64)
}
