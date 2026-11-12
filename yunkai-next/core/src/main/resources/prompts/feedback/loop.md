<!-- 主循环给模型的提醒、说明与参考资料的来源标签。每条以「## 键」开头,下面是原文;{{名字}} 由代码填。 -->

## empty_reply
Your reply was empty. Continue the task with your tools, or reply with your final answer to the user in text.

## truncated_reply
Your reply was cut off by the output limit before it was complete. Continue the task with your tools, or reply again with a shorter final answer.

## unconfirmed_nudge
Before this answer goes to the user:
{{findings}}
Check this: read the place again if you can, then reply with your corrected final answer. Anything you still cannot confirm, say plainly in the answer that you could not confirm it instead of stating it as fact. This check happens once; your next answer goes to the user.

## unconfirmed_gaps
These items in your answer do not appear in anything you read during this task:
{{items}}

## unconfirmed_objection
An independent check of your answer against what you read during this task found a problem:
{{reasons}}

## followup_nudge
Before this goes to the user: {{what}}, and nothing has been done with it since. Carry it out now, or, if you are not going to, tell the user plainly why.

## followup_approved
the user approved this action: {{action}}

## followup_answered
the user answered your question 「{{prompt}}」

## answer_or_failure
If the result the task asks for needs a feature, menu, setting, page, item or value that is not there, call end_not_done listing what you checked. Only a yes/no question about whether something exists is answered by its absence: if the app's own search gave a definitive none, reply that it does not exist, quoting the text it showed.

## give_up_advice
You keep repeating without progress. Stop revisiting the same places. If a specific missing choice or detail from the user would decide the next route, call ask_user and ask it once. Otherwise, continue only with a concrete untried route; if there is none, call end_not_done with the confirmed partial result, what is still missing and what you checked. {{answer_or_failure}}

## repeated_input_advice
You have already typed "{{text}}" {{times}} times in this task; typing it again will show the same result. If what you have checked shows the target is not there, stop. {{answer_or_failure}}

## progress_check
Progress check: {{used}} of {{max}} steps used and the task is not finished. If what you have checked so far (search inside the app, its main menus) shows the target is not there or cannot be reached, stop now. {{answer_or_failure}} Continue only if you have a concrete, untried next place to look.

## final_turn
You are out of steps; no more phone actions are possible. If the task is done, reply to the user with the result in text, stating only what you read. If it is not done or only partly done, call end_not_done with the confirmed partial result, what you checked and what is missing.

## screenshot_note
Current screenshot for the observation above.

## file_image_note
The image file read above.

## action_frame_note
The screen right after the call above. Act with ref + snapshotId from the elements in that result's screen field; the image is only for understanding, and its screenshotId is for view_past_screens, never a snapshotId.

## batch_view_note
The screen after the steps above. Act with ref + snapshotId from these elements; the image (if any) is only for understanding, and its screenshotId is for view_past_screens, never a snapshotId.
screen: {{screen}}

## recalled_note
Recalled earlier screenshots, in the order of the view_past_screens result entries marked imageAttached. They show past screens, not the current one.

## nudge_source
没有给出回答

## before_accept_source
回答之前收到的补充

## followup_source
还没照做

## unconfirmed_source
核对回答

## progress_source
步数自查

## final_source
步数用完

## replay_source
按以往的做法

## parallel_call_skipped
Phone actions run one at a time. Observe the result of the first action, then try again.

## replay.completed
已按以往的做法走完全部步骤({{total}} 步):
{{steps}}
请读一下 App 现在的状态核实结果;是查询类任务就从读到的内容里找出答案,然后直接回答用户。

## replay.progress
已按以往成功的做法自动完成 {{done}}/{{total}} 步:
{{steps}}
{{next}}


## replay.diverged_executed
第 {{step}} 步已执行,但没看到预期变化:预期 {{expected}},实际读到的内容 {{seen}}。先看清这一步有没有生效,别重复做。{{error}}请从当前界面或页面接着做。

## replay.diverged_skipped
第 {{step}} 步预期 {{expected}},实际读到的内容 {{seen}}(这一步没有执行)。{{error}}请从当前界面或页面接着做。

## replay.error_code
错误码:{{code}}。

## replay.no_steps
(无)

## replay.handback
{{head}}

{{note}}

## replay.stopped
第 {{step}} 步是「{{action}}」,需要用户确认,没有执行。请从当前界面或页面接着做,按正常流程处理确认。

## confirmation_unavailable
request_confirmation is not available.

## confirm_app_mismatch
app {{app}} is not the app on screen now ({{foreground}}). Observe, then call request_confirmation again with the package of the app on screen.

## batch.mixed_surface
These steps act on more than one screen ({{surfaces}}); one do_steps call stays on one screen. Nothing was run: split them into separate calls, one screen per batch.

## approved_step_first
先单独执行已同意的那一步:the user approved one action. Do exactly that action as a single tool call (not inside do_steps or web_script), then continue.

## replay_screen_header
当前界面(读取结果,编号可直接用):

## replay_no_screen
当前界面没能读出来,请先 observe 再接着做。

## confirmation_declined
用户没有同意,不要再尝试这个操作;汇报现状后结束

## confirmation_approved
用户同意了一次。马上做这一个操作,中间不要点别处、滑动、返回或切换 App(打字可以),3 步内有效;紧接着的下一个匹配操作无论成功与否都会用掉这次同意,再做要重新申请。

## no_answer_message
没有在时限内收到回答

## handback_note
交回只表示把手机还回来了,不代表这一步成功了。先读取当前界面核实结果,再继续或汇报。

## no_handback_note
没有发生交回。不要说成用户处理过了;如实汇报接管的结局,需要时换别的办法或结束。

## user_finished_note
用户说已自己完成

## user_finished_prompt
用户说已自己完成,请只 observe 读一下 App 现在的状态来核实:确认完成就直接回答用户读到的结果,无法确认就 end_not_done,不要再做其他操作。


## user_screen.sent
用户刚截下他正在看的屏幕({{app}},{{time}}),没有附言。

## user_screen.sent_with_words
用户刚截下他正在看的屏幕({{app}},{{time}}),附言:{{words}}

## user_screen.blocked
用户想把他正在看的屏幕发给你({{app}},{{time}}),但这个页面不让截图,画面和上面的字都没拿到;没有附言。

## user_screen.blocked_with_words
用户想把他正在看的屏幕发给你({{app}},{{time}}),但这个页面不让截图,画面和上面的字都没拿到;附言:{{words}}

## user_screen.missing
用户发来过他正在看的一屏({{id}}),但这张截图已经不在了。

## user_screen.earlier
(用户之前发来的屏幕 {{id}}:{{app}},{{time}};屏上开头是「{{gist}}」)

## user_screen.earlier_blank
(用户之前发来的屏幕 {{id}}:{{app}},{{time}};屏上没有读到字)

## user_screen.earlier_blocked
(用户之前想发来屏幕:{{app}},{{time}};那个页面不让截图,没截到)

## user_screen.detail
屏幕编号:{{id}}
App:{{app}}{{package}}
截下时刻:{{time}}
屏上的字(每行前面是它中心的位置 x,y,0–999,左上是 0,0;聊天里靠左的气泡是对方说的,靠右的是用户自己说的):
{{lines}}

## user_screen.no_text
屏上没有读到字。

## user_screen.ask_vision
你看不到这张图;要知道字以外的东西(图标、图片、颜色、版面)时,用 view_user_screen 带着问题问看图模型。

## user_screen.no_words
The user sent only this screen, without words. Work out from the screen what they most likely want. If it only needs an answer, reading, looking something up or searching (nothing gets written or changed anywhere), just do it. If it would write or change something (save an entry in another app, send, post, book, pay, delete, change a setting…), first ask once with ask_user, in one line, what you plan to do, with short options such as 「记一笔」/「不用」, and go ahead only if they agree. If you do not know which app they use for that kind of job, ask in the same question, and remember their choice if you can save memories.

## user_screen.blocked_reply
The page did not allow screenshots, so you cannot see it. Say so in one line and ask the user to switch to another page and send again, or to just tell you what to do.
