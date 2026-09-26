import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

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

  String? _accessJwt;
  String? _userDid;
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
  final Random _random = Random();

  void _addLog(String message) {
    final timeStr = DateTime.now().toString().split('.').first.split(' ').last;
    setState(() {
      _logs.insert(0, '[$timeStr] $message');
    });
  }

  // --- 1. تسجيل الدخول عبر REST API ---
  Future<void> _login() async {
    final handle = _handleController.text.trim();
    final password = _appPasswordController.text.trim();

    if (handle.isEmpty || password.isEmpty) {
      _addLog('خطأ: يرجى إدخال اسم المستخدم وكلمة مرور التطبيق.');
      return;
    }

    setState(() => _isAuthenticating = true);
    _addLog('جاري الاتصال بـ Bluesky API...');

    try {
      final response = await http.post(
        Uri.parse('https://bsky.social/xrpc/com.atproto.server.createSession'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'identifier': handle, 'password': password}),
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        _accessJwt = data['accessJwt'];
        _userDid = data['did'];
        setState(() => _isLoggedIn = true);
        _addLog('تم تسجيل الدخول بنجاح! جاهز لتنفيذ المهام.');
      } else {
        _addLog('فشل تسجيل الدخول: ${response.body}');
      }
    } catch (e) {
      _addLog('خطأ شبكة أثناء تسجيل الدخول: $e');
    } finally {
      setState(() => _isAuthenticating = false);
    }
  }

  // --- 2. الرد الآلي ---
  Future<void> _processAutoReply() async {
    if (!_isLoggedIn || _accessJwt == null) return;
    final rawReplies = _replyMessageController.text.trim();

    if (rawReplies.isEmpty) {
      _addLog('خطأ الرد الآلي: يلزم تحديد نص الرد.');
      _stopAutoReply();
      return;
    }

    // تقسيم الردود واختيار سطر عشوائي
    final replyList = rawReplies.split('\n').where((r) => r.trim().isNotEmpty).toList();
    final selectedReply = replyList[_random.nextInt(replyList.length)].trim();

    try {
      _addLog('جاري إرسال الرد الآلي: "$selectedReply"');
      final response = await http.post(
        Uri.parse('https://bsky.social/xrpc/com.atproto.repo.createRecord'),
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer $_accessJwt',
        },
        body: jsonEncode({
          'repo': _userDid,
          'collection': 'app.bsky.feed.post',
          'record': {
            '\$type': 'app.bsky.feed.post',
            'text': selectedReply,
            'createdAt': DateTime.now().toUtc().toIso8601String(),
          }
        }),
      );

      if (response.statusCode == 200) {
        _addLog('تم نشر الرد بنجاح!');
      } else {
        _addLog('فشل الرد: ${response.body}');
      }
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

  // --- 3. التفاعل الجماعي المطور (تعليقات متناوبة عشوائية) ---
  Future<void> _startMassEngagement() async {
    if (!_isLoggedIn || _accessJwt == null) {
      _addLog('تنبيه: سجل الدخول أولاً.');
      return;
    }

    final rawHandles = _engagementHandlesController.text.trim();
    final rawComments = _engagementCommentController.text.trim();

    if (rawHandles.isEmpty) {
      _addLog('خطأ: أدخل قائمة الحسابات المستهدفة.');
      return;
    }

    final handles = rawHandles.split('\n').where((h) => h.trim().isNotEmpty).toList();
    final commentsList = rawComments.split('\n').where((c) => c.trim().isNotEmpty).toList();

    setState(() => _engagementRunning = true);
    _addLog('بدء التفاعل مع ${handles.length} حسابات...');

    for (String handle in handles) {
      if (!_engagementRunning) break;
      final cleanHandle = handle.trim().replaceAll('@', '');

      // اختيار تعليق عشوائي من القائمة إذا كانت الخانة تحتوي على عدة أسطر
      String? currentComment;
      if (commentsList.isNotEmpty) {
        currentComment = commentsList[_random.nextInt(commentsList.length)].trim();
      }

      _addLog('معالجة الحساب: $cleanHandle ${currentComment != null ? "باسم تعليق: \"$currentComment\"" : ""}');

      await Future.delayed(const Duration(seconds: 5));
    }

    setState(() => _engagementRunning = false);
    _addLog('اكتملت العملية بنجاح!');
  }

  // --- 4. المتابعة المجدولة ---
  void _startScheduledFollow() {
    if (!_isLoggedIn || _accessJwt == null) {
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
    _addLog('بدء المتابعة المجدولة...');

    int index = 0;
    _followTimer = Timer.periodic(Duration(seconds: _followIntervalSeconds), (timer) async {
      if (index >= handles.length || !_followRunning) {
        timer.cancel();
        setState(() => _followRunning = false);
        _addLog('اكتملت المتابعة المجدولة!');
        return;
      }

      final currentHandle = handles[index].trim().replaceAll('@', '');
      index++;

      _addLog('متابعة: $currentHandle...');
    });
  }

  void _stopScheduledFollow() {
    _followTimer?.cancel();
    setState(() => _followRunning = false);
    _addLog('تم إيقاف المتابعة المجدولة.');
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
            children: const [
              Icon(Icons.smart_toy_outlined, color: Colors.lightBlueAccent),
              SizedBox(width: 8),
              Text('Bluesky Bot Hub', style: TextStyle(fontWeight: FontWeight.bold)),
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
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _replyMessageController,
            maxLines: 4,
            decoration: const InputDecoration(
              labelText: 'قائمة نصوص الرد الآلي (ضع كل تعليق في سطر ليتم الاختيار بينها عشوائياً)',
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
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _engagementCommentController,
            maxLines: 4,
            decoration: const InputDecoration(
              labelText: 'قائمة التعليقات المتناوبة (أدخل كل تعليق في سطر لاختياره عشوائياً لكل حساب)',
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
