const normalize = (query) => query.toLowerCase().replace(/[’‘]/g, "'").trim().replace(/[.!?,]+$/g, '').replace(/\s+/g, ' ');

export const isFollowUp = (query) => /^(what next|what's next|next step|tell me more|how do i do that|and then|what about that|can you explain that|explain that|explain more|say that again|can you simplify that|make it simpler|i don't understand|i dont understand|i'm confused|im confused|walk me through it|step by step|where do i start|what should i do)[?.! ]*$/i.test(normalize(query));

// Strip only social wrappers; keep the workplace request intact for the existing engine.
export function workplaceQuery(query) {
  return query.trim()
    .replace(/^(?:(?:hi|hello|hey)(?: there)?(?: wav[yi])?[!,.:\s]+)(?=\S)/i, '')
    .replace(/^(?:thanks|thank you)(?: wav[yi])?[,!.]\s*(?=\S)/i, '')
    .replace(/[,!]\s*(?:thanks|thank you)(?: wav[yi])?[!.]*$/i, '')
    .trim();
}

export function conversationalReply(query, { history = [], previousQuery = '', context = null } = {}) {
  const text = normalize(query).replace(/^(?:hey |hi )?wav[yi][,! ]+/, '');
  if (context?.intent === 'workflow-guide' && /^(next|continue|done|yes|yep|sure)$/.test(text)) return null;
  const recent = history.filter((message) => message.sender === 'assistant').slice(-3).map((message) => message.text);
  const reply = (choices, extra = {}) => ({ text: choices.find((choice) => !recent.includes(choice)) || choices[recent.length % choices.length], social: true, actions: [], ...extra });
  const topic = /timesheet|hours/i.test(previousQuery) ? 'your timesheet' : /profile|address/i.test(previousQuery) ? 'your profile request' : /document|letter/i.test(previousQuery) ? 'your document request' : /employee|person/i.test(previousQuery) ? 'finding someone' : 'that task';
  const introduction = query.trim().match(/^(?:my name is|call me) ([\p{L}][\p{L} '-]{0,49})[.!]*$/iu);
  if (introduction) return reply([`Nice to meet you, ${introduction[1].trim()}! What's on your mind today?`]);
  if (/^(what's my name|what is my name|do you remember my name|do you remember me)$/.test(text)) {
    const name = [...history].reverse().filter((message) => message.sender === 'user').map((message) => message.text.match(/^(?:my name is|call me) ([\p{L}][\p{L} '-]{0,49})[.!]*$/iu)?.[1]).find(Boolean);
    return reply([name ? `You told me to call you ${name.trim()}. I can use what's in our recent conversation, but I don't keep a personal memory after a restart.` : "You haven't told me a name in our recent chat. What would you like me to call you?"]);
  }
  if (/^(can we (just )?(chat|talk)|i (just )?want to (chat|talk)|let's (chat|talk)|keep me company|can i talk to you)$/.test(text)) {
    return reply(["Of course. What's on your mind: your day, something at work, or just a quick break?", "Sure, we can chat. How has your day been treating you?"]);
  }
  if (/^(how was your day|how's your day|how is your day|what are you doing)$/.test(text)) {
    return reply(["I'm here helping with Waypoint questions. How's your day going?", "Ready for the next question, or a little workday chat. How are things with you?"]);
  }
  if (/^(i (had|have had) a (long|rough|hard|busy) day|it's been a (long|rough|hard) day|work is (stressful|exhausting)|too much work)$/.test(text)) {
    return reply(["That sounds draining. Want a quick breather, or shall we pick one small task to make the day lighter?", "Some days pile up. What's the one thing you'd most like off your plate?"]);
  }
  if (/^(how are you|how're you|how r u|how are you doing|how's it going|hows it going|what's up|whats up|sup|you good)( wav[yi])?$/.test(text)) {
    return reply(["Ready to help and keeping the busywork in check. How's your day going?", "I'm here and ready to lend a hand. How are things on your side?", "All set for whatever your workday brings. What's on your mind?"]);
  }
  if (/^(i'm |im |i am )?(good|great|fine|doing well|doing good|pretty good|not bad|happy|feeling good)( today)?(?: how about you| and you)?$/.test(text)) {
    return reply(["Glad to hear it! What's on your plate today?", "Nice! Anything I can help you get out of the way?", "Good to hear. I'm here if you want a hand with anything."]);
  }
  if (/^(i'm |im |i am |feeling |i feel )?(stressed|overwhelmed|tired|exhausted|frustrated|having a bad day|not doing well|sad|bored|busy|so busy)( today| with work)?$/.test(text)) {
    return reply(["Sounds like a lot today. Want to pick one small thing to tackle together?", "Sorry it's been a tough day. Tell me what's taking up the most energy, and we can start there."]);
  }
  if (/^(this (isn't|is not|isnt) working|it (didn't|did not|didnt) work|that (didn't|did not|didnt) work|i'm stuck|im stuck|i am stuck|still stuck|not helpful|that's wrong|thats wrong|you're wrong|you are wrong|you misunderstood|that's not what i meant|thats not what i meant)$/.test(text)) {
    return reply([previousQuery ? `Let's take another look at ${topic}. What happened: an error message, a missing option, or something else?` : "Let's figure it out. What were you trying to do, and what happened instead?", "Tell me where it went off track. If there's an error message, you can share its wording without including personal details."], { preserveTask: true });
  }
  if (/^(tell me (a joke|something funny)|make me laugh|joke|another joke|one more joke)$/.test(text)) {
    return reply(["Why did the spreadsheet get promoted? It excelled at its job. I'll leave the comedy career on hold.", "My favorite workplace shortcut? Taking the short walk to refill your water. The keyboard ones are useful too.", "Why did the calendar look so popular? Its dates were all booked."]);
  }
  if (/^(are you (a )?(human|real|person|robot|ai)|are you my friend|can we be friends|who made you|who built you)$/.test(text)) {
    return reply(["I'm Wavy, a software assistant in Waypoint. Think of me as a friendly workday sidekick: I can chat, guide you through tasks, and check the app information your account can access."]);
  }
  if (/^(you're (awesome|great|helpful|the best|amazing)|you are (awesome|great|helpful|the best|amazing)|good job|well done|love it|i like you)$/.test(text)) {
    return reply(["Glad I could help. One less thing on your list!", "That means the shortcut did its job. What shall we tackle next?"]);
  }
  if (/^(never mind|nevermind|forget it|cancel|stop|let's start over|start over|change (the )?topic|something else)$/.test(text)) {
    return reply(["No problem. Let's start fresh. What's on your mind?"], { context: null, clearTask: true });
  }
  if (/^(hi|hello|hey|hey there|good morning|good afternoon|good evening)( wav[yi])?$/.test(text)) {
    return reply(['Hey there! 👋 What can I help you with today?', "Hey! Good to hear from you. What's on your mind?", "Hi again! Ready when you are. What are we working on?"], { mood: 'greeting' });
  }
  if (/^(thanks|thank you|thank you so much|thank you very much|thanks so much|thanks a lot|thanks wav[yi]|thank you wav[yi]|thanks for your help|thank you for your help|cheers|appreciate it|appreciate your help)$/.test(text)) {
    return reply(["Anytime! That's what I'm here for. ✨", "You're welcome! Glad that helped.", "Happy to help. Give me a shout if anything else comes up."]);
  }
  if (/^(alright|all right|ok|okay|ok cool|okay cool|got it|understood|sounds good|you got it|great|perfect|cool|nice|sure|yes|yep)$/.test(text)) {
    if (context?.intent === 'employee-search') return reply([context.field ? `Whenever you're ready, send the ${context.field === 'employeeId' ? 'employee ID' : context.field}.` : 'Sure. Would you like to search by name, employee ID, or designation?']);
    return reply(['You got it! Anything else on your mind?', "Sounds good. I'm here if you need the next step.", "All right! What would you like to tackle next?"]);
  }
  if (/^(no|nope|nothing else|that's all|all done|bye|goodbye)$/.test(text)) {
    return reply(["You're all set. I'll be here when you need a hand.", "Take care! You can pick this up whenever you're ready."], { context: null, clearTask: true });
  }
  if (/^(who are you|what can you do|help)[? ]*$/.test(text)) {
    return { text: "I'm Wavy, your Waypoint buddy. I can guide you through a task, find a useful page, or check information your account has access to. What are you working on?", social: true, actions: [] };
  }
  return null;
}

export function employeeSearchIntent(query, context) {
  const direct = query.match(/^(?:i (?:need|want) to |can you |please )?(?:find|search(?: for)?|look(?:ing)? for|locate)(?: an?| the)? (?:employee|someone|person)(?:\s+(.*))?[?.!]*$/i);
  if (!direct && context?.intent !== 'employee-search') return null;
  if (!direct && /^(how|when|what|why|check|show|open|take me|my timesheets|my documents)\b/i.test(query)) return null;
  let value = (direct?.[1] || (!direct ? query : '')).trim().replace(/[?.!]+$/, '');
  const fieldMatch = value.match(/^(?:by\s+)?(name|employee id|id|designation|job title)\s*:?\s*(.*)$/i);
  let field = context?.field || null;
  if (fieldMatch) {
    field = /id/i.test(fieldMatch[1]) ? 'employeeId' : /designation|title/i.test(fieldMatch[1]) ? 'designation' : 'name';
    value = fieldMatch[2].trim();
  }
  value = value.replace(/^(?:named|called)\s+/i, '').replace(/^['"]|['"]$/g, '');
  return { intent: 'employee-search', field, value };
}
