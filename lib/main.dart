import 'dart:async';
import 'package:flutter/material.dart';

import 'package:bluesky/bluesky.dart' as bsky;
import 'package:atproto_core/atproto_core.dart' as atp;

void main() {
  runApp(const BlueskyAutomationApp());
}

class BlueskyAutomationApp extends StatelessWidget {
  const BlueskyAutomationApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Bluesky Automation Hub',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF0085FF),
          brightness: Brightness.dark,
        ),
        scaffoldBackgroundColor: const Color(0xFF0F172A),
        appBarTheme: const AppBarTheme(
          backgroundColor: Color(0xFF1E293B),
          centerTitle: true,
          elevation: 0,
        ),
      ),
      home: const MainAutomationScreen(),
    );
  }
}

class MainAutomationScreen extends StatefulWidget {
  const MainAutomationScreen({super.key});

  @override
  State<MainAutomationScreen> createState() => _MainAutomationScreenState();
}

class _MainAutomationScreenState extends State<MainAutomationScreen> {
  final _handleController = TextEditingController();
  final _appPasswordController = TextEditingController();

  bsky.Bluesky? _bluesky;
  bool _isLoggedIn = false;
  bool _isAuthenticating = false;

  final _targetPostUriController = TextEditingController();
  final _replyMessageController = TextEditingController();
  bool _autoReplyRunning = false;
  Timer? _autoReplyTimer;

  final _engagementHandlesController = TextEditingController();
  final _engagementCommentController = TextEditingController();
  bool _engagementRunning = false;

  final _followHandlesController = TextEditingController();
  int _followIntervalSeconds = 30;
  bool _followRunning = false;
  Timer? _followTimer;

  final List<String> _logs = [];

  void _addLog(String message) {
    final timeStr = DateTime.now().toString().split('.').first.split(' ').last;
    setState(() {
      _logs.insert(0, '[$timeStr] $message');
    });
  }

  Future<void> _login() async {
    final handle = _handleController.text.trim();
    final password = _appPasswordController.text.trim();

    if (handle.isEmpty || password.isEmpty) {
      _addLog('خطأ: يرجى إدخال اسم المستخدم وكلمة مرور التطبيق.');
      return;
    }

    setState(() => _isAuthenticating = true);
    _addLog('جاري الاتصال بـ Bluesky...');

    try {
      _bluesky = bsky.Bluesky.fromSession(
        await bsky.createSession(
          service: 'bsky.social',
          identifier: handle,
          password: password,
        ).then((res) => res.data),
      );

      setState(() {
        _isLoggedIn = true;
      });
      _addLog('تم تسجيل الدخول بنجاح! جاهز لتنفيذ المهام.');
    } catch (e) {
      _addLog('فشل تسجيل الدخول: $e');
    } finally {
      setState(() => _isAuthenticating = false);
    }
  }

  Future<void> _processAutoReply() async {
    if (_bluesky == null) return;
    final postUriStr = _targetPostUriController.text.trim();
    final replyText = _replyMessageController.text.trim();

    if (postUriStr.isEmpty || replyText.isEmpty) {
      _addLog('خطأ الرد الآلي: يلزم تحديد رابط/URI المنشور ونص الرد.');
      _stopAutoReply();
      return;
    }

    try {
      _addLog('فحص المنشور المستهدف ورصد التعليقات...');
      
      final atUri = atp.AtUri.parse(postUriStr);
      final thread = await _bluesky!.feed.getPostThread(uri: atUri);
      
      thread.data.thread.when(
        record: (data) async {
          final targetPost = data.post;
          final postRef = bsky.StrongRef(
            cid: targetPost.cid,
            uri: targetPost.uri,
          );

          await _bluesky!.feed.createPost(
            text: replyText,
            reply: bsky.ReplyRef(
              root: postRef,
              parent: postRef,
            ),
          );

          _addLog('تم إرسال الرد بنجاح على المنشور المستهدف!');
        },
        notFound: (_) => _addLog('خطأ: لم يتم العثور على المنشور المطلوب.'),
        blocked: (_) => _addLog('خطأ: لا يمكن الوصول للمنشور (حظر).'),
        unknown: (_) => _addLog('خطأ غير معروف أثناء فحص المنشور.'),
      );
    } catch (e) {
      _addLog('خطأ أثناء تنفيذ الرد الآلي: $e');
    }
  }

  void _toggleAutoReply(bool start) {
    if (start) {
      if (!_isLoggedIn) {
        _addLog('تنبيه: يجب تسجيل الدخول أولاً.');
        return;
      }
      setState(() => _autoReplyRunning = true);
      _addLog('بدء تشغيل خدمة الرد الآلي...');
      _processAutoReply();
      _autoReplyTimer = Timer.periodic(const Duration(minutes: 2), (_) {
        _processAutoReply();
      });
    } else {
      _stopAutoReply();
    }
  }

  void _stopAutoReply() {
    _autoReplyTimer?.cancel();
    setState(() => _autoReplyRunning = false);
    _addLog('تم إيقاف خدمة الرد الآلي.');
  }

  Future<void> _startMassEngagement() async {
    if (!_isLoggedIn || _bluesky == null) {
      _addLog('تنبيه: سجل الدخول أولاً.');
      return;
    }

    final rawHandles = _engagementHandlesController.text.trim();
    final comment = _engagementCommentController.text.trim();

    if (rawHandles.isEmpty) {
      _addLog('خطأ: أدخل قائمة الحسابات المستهدفة.');
      return;
    }

    final handles = rawHandles.split('\n').where((h) => h.trim().isNotEmpty).toList();
    setState(() => _engagementRunning = true);
    _addLog('بدء حملة التفاعل الجماعي على ${handles.length} حسابات...');

    for (String handle in handles) {
      if (!_engagementRunning) break;
      final cleanHandle = handle.trim().replaceAll('@', '');

      try {
        _addLog('جاري معالجة الحساب: $cleanHandle');
        
        final actor = await _bluesky!.actors.searchActors(text: cleanHandle);
        if (actor.data.actors.isEmpty) {
          _addLog('تعذر العثور على الحساب: $cleanHandle');
          continue;
        }

        final targetDid = actor.data.actors.first.did;
        final feed = await _bluesky!.feed.getAuthorFeed(actor: targetDid, limit: 1);
        if (feed.data.feed.isNotEmpty) {
          final latestPost = feed.data.feed.first.post;

          await _bluesky!.feed.createLike(
            uri: latestPost.uri,
            cid: latestPost.cid,
          );
          _addLog('تم الإعجاب بآخر منشور لـ $cleanHandle');

          if (comment.isNotEmpty) {
            final ref = bsky.StrongRef(cid: latestPost.cid, uri: latestPost.uri);
            await _bluesky!.feed.createPost(
              text: comment,
              reply: bsky.ReplyRef(root: ref, parent: ref),
            );
            _addLog('تم إرسال التعليق لـ $cleanHandle');
          }
        } else {
          _addLog('لا توجد منشورات متاحة للحساب $cleanHandle');
        }

        await Future.delayed(const Duration(seconds: 10));
      } catch (e) {
        _addLog('خطأ مع الحساب $cleanHandle: $e');
      }
    }

    setState(() => _engagementRunning = false);
    _addLog('اكتملت عملية التفاعل الجماعي!');
  }

  void _startScheduledFollow() {
    if (!_isLoggedIn || _bluesky == null) {
      _addLog('تنبيه: سجل الدخول أولاً.');
      return;
    }

    final rawHandles = _followHandlesController.text.trim();
    if (rawHandles.isEmpty) {
      _addLog('خطأ: أدخل قائمة الحسابات للمتابعة.');
      return;
    }

    final handles = rawHandles.split('\n').where((h) => h.trim().isNotEmpty).toList();
    if (handles.isEmpty) return;

    setState(() => _followRunning = true);
    _addLog('بدء المتابعة المجدولة بفارق $_followIntervalSeconds ثانية لكل حساب...');

    int index = 0;
    _followTimer = Timer.periodic(Duration(seconds: _followIntervalSeconds), (timer) async {
      if (index >= handles.length || !_followRunning) {
        timer.cancel();
        setState(() => _followRunning = false);
        _addLog('اكتملت قائمة المتابعة المجدولة!');
        return;
      }

      final currentHandle = handles[index].trim().replaceAll('@', '');
      index++;

      try {
        _addLog('جاري متابعة: $currentHandle');
        final actor = await _bluesky!.actors.searchActors(text: currentHandle);
        if (actor.data.actors.isNotEmpty) {
          final targetDid = actor.data.actors.first.did;
          await _bluesky!.graphs.createFollow(did: targetDid);
          _addLog('تمت متابعة $currentHandle بنجاح!');
        } else {
          _addLog('لم يتم العثور على $currentHandle لتنفيذ المتابعة.');
        }
      } catch (e) {
        _addLog('خطأ أثناء متابعة $currentHandle: $e');
      }
    });
  }

  void _stopScheduledFollow() {
    _followTimer?.cancel();
    setState(() => _followRunning = false);
    _addLog('تم إيقاف خدمة المتابعة المجدولة.');
  }

  @override
  void dispose() {
    _autoReplyTimer?.cancel();
    _followTimer?.cancel();
    _handleController.dispose();
    _appPasswordController.dispose();
    _targetPostUriController.dispose();
    _replyMessageController.dispose();
    _engagementHandlesController.dispose();
    _engagementCommentController.dispose();
    _followHandlesController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 4,
      child: Scaffold(
        appBar: AppBar(
          title: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.smart_toy_outlined, color: Colors.lightBlueAccent),
              const SizedBox(width: 8),
              const Text('Bluesky Bot Hub', style: TextStyle(fontWeight: FontWeight.bold)),
            ],
          ),
          bottom: const TabBar(
            isScrollable: true,
            indicatorColor: Colors.lightBlueAccent,
            tabs: [
              Tab(icon: Icon(Icons.login), text: 'الحساب'),
              Tab(icon: Icon(Icons.reply), text: 'الرد الآلي'),
              Tab(icon: Icon(Icons.bolt), text: 'التفاعل الجماعي'),
              Tab(icon: Icon(Icons.person_add), text: 'المتابعة المجدولة'),
            ],
          ),
        ),
        body: Column(
          children: [
            Expanded(
              child: TabBarView(
                children: [
                  _buildAccountTab(),
                  _buildAutoReplyTab(),
                  _buildEngagementTab(),
                  _buildFollowTab(),
                ],
              ),
            ),
            _buildLogViewer(),
          ],
        ),
      ),
    );
  }

  Widget _buildAccountTab() {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: ListView(
        children: [
          Card(
            color: const Color(0xFF1E293B),
            child: Padding(
              padding: const EdgeInsets.all(16.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('إعدادات الاتصال بـ Bluesky', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                  const SizedBox(height: 15),
                  TextField(
                    controller: _handleController,
                    decoration: const InputDecoration(
                      labelText: 'المعرف (Handle)',
                      hintText: 'user.bsky.social',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _appPasswordController,
                    obscureText: true,
                    decoration: const InputDecoration(
                      labelText: 'كلمة مرور التطبيق (App Password)',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  const SizedBox(height: 20),
                  SizedBox(
                    width: double.infinity,
                    height: 48,
                    child: ElevatedButton(
                      onPressed: _isAuthenticating ? null : _login,
                      style: ElevatedButton.styleFrom(backgroundColor: Colors.blueAccent),
                      child: _isAuthenticating
                          ? const CircularProgressIndicator(color: Colors.white)
                          : Text(_isLoggedIn ? 'إعادة تسجيل الدخول' : 'تسجيل الدخول الآن'),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAutoReplyTab() {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: ListView(
        children: [
          TextField(
            controller: _targetPostUriController,
            decoration: const InputDecoration(
              labelText: 'رابط / AT URI للمنشور المستهدف',
              hintText: 'at://did:plc:.../app.bsky.feed.post/...',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _replyMessageController,
            maxLines: 3,
            decoration: const InputDecoration(
              labelText: 'نص الرد الآلي',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: ElevatedButton.icon(
                  onPressed: _autoReplyRunning ? null : () => _toggleAutoReply(true),
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('تشغيل الرد الآلي'),
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.green),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: ElevatedButton.icon(
                  onPressed: !_autoReplyRunning ? null : () => _toggleAutoReply(false),
                  icon: const Icon(Icons.stop),
                  label: const Text('إيقاف'),
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.redAccent),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildEngagementTab() {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: ListView(
        children: [
          TextField(
            controller: _engagementHandlesController,
            maxLines: 4,
            decoration: const InputDecoration(
              labelText: 'قائمة الحسابات المستهدفة (اسم في كل سطر)',
              hintText: 'user1.bsky.social\nuser2.bsky.social',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _engagementCommentController,
            decoration: const InputDecoration(
              labelText: 'نص التعليق على آخر منشور (اختياري)',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            height: 48,
            child: ElevatedButton.icon(
              onPressed: _engagementRunning ? null : _startMassEngagement,
              icon: const Icon(Icons.flash_on),
              label: Text(_engagementRunning ? 'جاري التنفيذ...' : 'بدء التفاعل الجماعي'),
              style: ElevatedButton.styleFrom(backgroundColor: Colors.orangeAccent),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFollowTab() {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: ListView(
        children: [
          TextField(
            controller: _followHandlesController,
            maxLines: 4,
            decoration: const InputDecoration(
              labelText: 'قائمة الحسابات للمتابعة (اسم في كل سطر)',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              const Text('الفارق الزمني بين كل متابعة:'),
              const Spacer(),
              DropdownButton<int>(
                value: _followIntervalSeconds,
                items: const [
                  DropdownMenuItem(value: 15, child: Text('15 ثانية')),
                  DropdownMenuItem(value: 30, child: Text('30 ثانية')),
                  DropdownMenuItem(value: 60, child: Text('دقيقة واحدة')),
                ],
                onChanged: (val) {
                  if (val != null) setState(() => _followIntervalSeconds = val);
                },
              ),
            ],
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: ElevatedButton.icon(
                  onPressed: _followRunning ? null : _startScheduledFollow,
                  icon: const Icon(Icons.person_add),
                  label: const Text('بدء المتابعة المجدولة'),
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.teal),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: ElevatedButton.icon(
                  onPressed: !_followRunning ? null : _stopScheduledFollow,
                  icon: const Icon(Icons.pause),
                  label: const Text('إيقاف'),
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.redAccent),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildLogViewer() {
    return Container(
      height: 180,
      width: double.infinity,
      color: Colors.black45,
      padding: const EdgeInsets.all(8.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('سجل السجلات والعمليات الحية:', style: TextStyle(color: Colors.grey, fontSize: 12)),
          const Divider(color: Colors.white24),
          Expanded(
            child: ListView.builder(
              itemCount: _logs.length,
              itemBuilder: (context, index) {
                return Text(
                  _logs[index],
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 11, color: Colors.greenAccent),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
