package com.qmusic.wear.ui.agreement

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.ui.components.PageTitle
import kotlinx.coroutines.delay

/**
 * 用户协议与免责声明（首次启动强制展示）：
 * - 同意 → 记录版本号并进入应用
 * - 不同意 → 退出应用
 *
 * 阅读约束：
 * - 「同意并继续」固定在正文最底部，必须滚动到协议末尾才能点击（确保完整阅读）
 * - 「不再提示」自协议出现起 10 秒倒计时后才可点击
 */
@Composable
fun AgreementScreen(
    onAgree: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // 「不再提示」倒计时（秒）：自协议出现起 10 秒
    var countdown by remember { mutableIntStateOf(10) }
    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    ScreenScaffold(timeText = { TimeText() }) { contentPadding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            PageTitle("用户协议")
            Text(
                "v1.2 · 2026-09-15 生效",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            // 正文：圆表中间区域可滚动，同意按钮固定在最底部
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SECTIONS.forEach { (title, body) ->
                    Section(title, body)
                }
                Text(
                    "你已阅读全部条款，点击即表示理解并同意本协议内容。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                // 同意按钮：放在正文最底部，必须完整浏览协议才能点到
                Button(
                    onClick = onAgree,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("同意并继续", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(10.dp))
            }

            // 固定底栏：不再提示（10 秒倒计时）+ 不同意并退出
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onAgree,
                    enabled = countdown == 0,
                ) {
                    Text(
                        if (countdown > 0) "不再提示 ${countdown}s" else "不再提示",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                OutlinedButton(
                    onClick = { (context as? Activity)?.finishAffinity() },
                ) {
                    Text("不同意并退出", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

/** 协议正文（修订条款时同步 AgreementStore.AGREEMENT_VERSION +1） */
private val SECTIONS: List<Pair<String, String>> = listOf(
    "一、协议的接受与变更" to
        "1. 本协议是你（以下简称“用户”）与本软件开发者（以下简称“开发者”）之间关于下载、安装、复制或以任何方式使用本软件及其配套功能所订立的协议。\n" +
        "2. 用户下载、安装、复制或以任何方式使用本软件，即视为已完整阅读、充分理解并同意接受本协议全部条款的约束；若不同意本协议任何内容，应立即点击“不同意并退出”、停止使用并从设备中删除本软件。\n" +
        "3. 用户确认具备完全民事行为能力；若用户为无民事行为能力人或限制民事行为能力人，应在监护人阅读、同意并指导下使用本软件。\n" +
        "4. 开发者有权根据法律法规变化、功能调整等需要随时修订本协议，修订后的协议将以应用内弹窗等方式向用户提示；协议更新后用户继续使用本软件的，视为接受修订后的协议；不接受修订内容的，应停止使用并卸载本软件。",

    "二、软件性质与使用许可" to
        "1. 本软件系个人开发者基于学习、研究与技术交流目的开发的非官方、非商业性开源软件，与腾讯公司及其“QQ音乐”产品不存在任何隶属、代理、合作、授权或关联关系，亦非其官方客户端。\n" +
        "2. 开发者在此授予用户免费的、不可转让的、非独占的、仅限个人学习研究与技术交流目的使用的普通许可。未经开发者书面许可，用户不得将本软件用于任何商业或营利用途，不得对本软件进行再分发、收费提供或批量传播。\n" +
        "3. 本软件不收取任何费用、不含任何付费功能，开发者亦不通过本软件获取任何直接或间接收益。",

    "三、知识产权声明" to
        "1. 本软件的源代码遵循其开源许可证发布；除涉及第三方权利的部分外，本软件的相关权利归开发者所有。\n" +
        "2. 通过本软件呈现或获取的音乐作品、歌词、封面图片、艺人信息、歌单等全部内容，均非本软件制作、编辑或存储，其版权归属于原作者、表演者、唱片公司、腾讯公司及其他相应权利方，本软件对其不享有任何权利。\n" +
        "3. “QQ音乐”及相关名称、标识为腾讯公司或其权利人的商标；本软件中出现的相关名称与标识仅用于客观描述内容来源，不构成任何商标性使用或授权。\n" +
        "4. 本软件使用的第三方开源组件版权归其原作者所有，以其各自的开源许可证为准。",

    "四、第三方接口与内容免责" to
        "1. 本软件通过第三方公开网络接口获取上述内容，该等接口不属于开发者控制范围。因第三方接口变更、失效、限流、鉴权调整、版权或地域限制等导致本软件功能部分或全部不可用、不稳定或不准确的，开发者不承担任何责任，亦不承诺持续维护、更新或修复。\n" +
        "2. 用户使用本人的第三方账号（如 QQ 账号）登录本软件的，视为其自行决定并授权本软件以用户名义向第三方服务发起请求；由此产生的一切后果与风险（包括但不限于账号被限制、冻结、封禁、凭据失效等）由用户自行承担。\n" +
        "3. 通过本软件获取的全部内容仅限用户个人学习与测试，严禁传播、二次分发、公开表演或用于任何商业用途；用户应以合法、正当方式获取正版内容，支持正版。",

    "五、下载功能与本地文件" to
        "1. 本软件提供的下载/缓存功能仅服务于个人学习与研究目的；下载内容存储于用户设备本地，属临时性学习材料，用户应在合理期限内自行删除。\n" +
        "2. 用户不得将下载内容以任何形式传播、出售或向公众提供；因用户下载、保存、使用本地文件的行为引发的任何版权纠纷及法律责任，均由用户自行承担，与开发者无关。",

    "六、隐私与数据处理" to
        "1. 本软件不设任何开发者自有服务器，不收集、不存储、不上传用户的任何个人信息。\n" +
        "2. 用户的登录凭据仅保存在用户设备本地存储中，仅用于向第三方服务发起请求；删除本软件或执行退出登录即可清除。\n" +
        "3. 本软件产生的崩溃日志等诊断信息仅保存在设备本地、仅用于故障排查，不会自动上传；用户可通过卸载本软件彻底删除全部本地数据。",

    "七、免责声明（无担保条款）" to
        "1. 本软件按“现状”和“现有”基础提供。在适用法律允许的最大范围内，开发者不对本软件作出任何形式的明示或默示担保，包括但不限于对适销性、特定用途适用性、不侵权、持续可用性、准确性、完整性、无错误或服务不中断的担保。\n" +
        "2. 对于因使用或无法使用本软件而导致的任何直接、间接、附带、特殊、惩罚性损失或后果（包括但不限于数据丢失、设备故障、账号损失、流量与电量费用、业务中断或利润损失），无论基于合同、侵权或其他任何责任形式，开发者均不承担责任。\n" +
        "3. 因不可抗力（包括但不限于自然灾害、网络故障、电力中断、法律政策调整、行政行为、第三方服务变更或中断等）导致的一切后果，开发者不承担责任。",

    "八、责任限制" to
        "1. 在适用法律允许的最大范围内，开发者就本软件对用户承担的全部责任（如经法律认定成立）不超过用户为获取本软件所实际支付的对价，即零元。\n" +
        "2. 前述免责与责任限制条款在适用法律不允许的范围内部分无效的，以法律允许的最大范围为限继续适用，且不影响其余条款的效力。",

    "九、用户行为规范" to
        "1. 用户承诺遵守中华人民共和国法律及所在国家/地区的法律法规，不利用本软件从事任何违法违规活动，包括但不限于侵犯他人著作权、传播违法违规信息、危害网络安全等。\n" +
        "2. 用户不得对本软件或第三方服务实施自动化批量请求、恶意攻击、逆向干扰或其他影响服务正常运行的行为。\n" +
        "3. 因用户违反本条约定而产生的一切法律后果由用户自行承担；造成开发者或任何第三方损失的，用户应承担相应的赔偿责任。",

    "十、未成年人保护" to
        "1. 本软件面向学习与研究群体。未成年人使用本软件，应在监护人阅读并同意本协议后、在监护人的同意与指导下进行。\n" +
        "2. 监护人应履行监督职责，控制未成年人的使用时长与使用方式，避免沉迷或不当使用。",

    "十一、法律适用与争议解决" to
        "1. 本协议的订立、效力、解释、履行及争议解决均适用中华人民共和国法律。\n" +
        "2. 因本协议或本软件产生的任何争议，双方应首先友好协商解决；协商不成的，任何一方均可向开发者所在地有管辖权的人民法院提起诉讼。\n" +
        "3. 可分割性：本协议任何条款被有权机关认定为全部或部分无效、可撤销或不可执行的，不影响本协议其他条款的效力，其他条款仍应继续履行。\n" +
        "4. 开发者未行使或延迟行使本协议项下的任何权利，不构成对该权利的放弃。",

    "十二、侵权通知与处理" to
        "1. 若你是本软件所涉内容的权利人或其代理人，认为本软件侵害了你的合法权益，请通过开源项目主页（github.com/ZhaoyuanGuo/QQMusicWear）提交书面通知并附权属证明材料。\n" +
        "2. 经核实属实后，开发者将依法及时停止相关内容的呈现或功能。\n" +
        "3. 任何未经核实的通知不产生中止本软件运行的效果。",

    "十三、音乐源插件机制" to
        "1. 本软件的协议实现以“音乐源插件”（JavaScript 脚本）形式在首次启动时从开发者发布的公开镜像地址自动下载，并在设备本地运行；APK 安装包内不包含该插件及任何第三方接口实现。\n" +
        "2. 插件在设备本地沙箱引擎中运行，仅具备网络请求、本地凭据读取、哈希计算等有限能力；插件经过开发者数字签名校验，校验不通过的文件将被拒绝执行。用户亦可在镜像不可用时手动导入开发者发布的插件文件，导入同样强制签名校验。\n" +
        "3. 插件的全部行为对外公开（见项目仓库 source/ 目录与 docs/ 开发指南），开发者承诺插件不收集、不上传用户个人信息；若未来插件内容发生变更，将以新版本下载方式发布并受同样的签名校验约束。\n" +
        "4. 插件下载与更新依赖第三方 CDN 及代码托管服务，因该等服务变更、失效导致的插件获取失败，开发者不承担责任；由此导致功能不可用的，用户可停止使用本软件。",

    "十四、其他" to
        "1. 本协议构成用户与开发者之间关于本软件使用的完整约定，并取代此前任何口头或书面的沟通、声明与谅解。\n" +
        "2. 本协议条款标题仅为阅读方便而设，不影响条款的解释。\n" +
        "3. 用户不得将本协议或本软件项下的任何权利义务转让给第三方。\n" +
        "4. 下载、安装或使用本软件，即表示你已阅读、理解并同意本协议的全部内容；如不同意，请点击“不同意并退出”并卸载本软件。",
)
