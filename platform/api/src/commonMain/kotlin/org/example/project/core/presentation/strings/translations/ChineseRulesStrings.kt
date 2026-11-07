package org.example.project.core.presentation.strings.translations

import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.RulesKeys

object ChineseRulesStrings {
    val all: Map<StringKey, String> =
        mapOf(
            RulesKeys.roundRobinAssignment to "轮值员工分配",
            RulesKeys.roundRobinAssignmentDescription to "打开空桌时,自动从轮值池中按顺序分配下一位员工",
            RulesKeys.rotationPoolHeader to "轮值池",
            RulesKeys.rotationPoolDescription to "纳入轮值的员工。仅限管理员设置。",
            RulesKeys.rotationPoolEmptyToast to "轮值池中没有员工，请在轮值员工分配中添加员工。",
            RulesKeys.rotationNextLabel to "下一位：{0}",
            RulesKeys.rotationNextContentDescription to "下一桌将分配给 {0}",
            RulesKeys.rotationShiftedToast to "轮值已变动，已为 {0} 开台",
            RulesKeys.stationTableDeniedTitle to "无法开台",
            RulesKeys.stationTableRequiresRotation to "点单站仅在启用轮值员工分配时才能开空桌。请在收银台开台。",
            RulesKeys.stationCannotOpenVirtualTable to "点单站无法打开外卖桌或虚拟桌。请在收银台开台。",
            RulesKeys.managerOnly to "仅限管理员",
        )
}
