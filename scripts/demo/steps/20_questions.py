"""Step 20 (A owns questions and assessments; first version by S3): a Data Structures question bank and the published
mid-term, through the real governance: faculty write, the designer reviews, a controller approves each question; the
designer builds the paper, a second controller reviews it and the first publishes it (three people).

Objective types only for now. Descriptive questions are added once manual evaluation (mandatory G) can mark them; until
then their results would stay "awaiting evaluation" (C-1).
"""
import json

import spec

OWNER = "A"
SUMMARY = "Data Structures bank (12 approved questions) and the published DS mid-term paper"
NEEDS = ["10_platform", "11_personas"]

CSE = spec.U(0xd121)


def content(text):
    return {"format": "ivorovi.content.v1", "blocks": [{"type": "paragraph", "runs": [{"text": text}]}]}


def single(stem, options, correct, marks=2):
    return {"type": "SINGLE_CHOICE", "content": content(stem), "marks": marks, "negativeMarks": 0.5,
            "body": {"options": [{"id": chr(65 + i), "content": content(o)} for i, o in enumerate(options)]},
            "answerKey": {"correct": correct}, "explanation": f"The correct answer is {correct}."}


def numeric(stem, value, marks=3):
    return {"type": "NUMERIC", "content": content(stem), "marks": marks, "body": {"precision": 0},
            "answerKey": {"value": value, "tolerance": {"type": "ABSOLUTE", "value": "0"}}}


# synthetic questions; the answer key is protected by the platform (ADR-0009) and never shown to candidates
QUESTIONS = [
    single("Which data structure follows last-in, first-out order?", ["Queue", "Stack", "Heap", "Linked list"], "B"),
    single("What is the worst-case time to search an unbalanced binary search tree with n nodes?", ["O(1)", "O(log n)", "O(n)", "O(n log n)"], "C"),
    single("Which traversal of a binary search tree visits the keys in sorted order?", ["Pre-order", "In-order", "Post-order", "Level-order"], "B"),
    single("A hash table resolves collisions by chaining. What is stored in each bucket?", ["A list of entries", "One entry only", "A stack of keys", "Nothing"], "A"),
    single("Which structure is best suited to a breadth-first search frontier?", ["Stack", "Queue", "Binary heap", "Trie"], "B"),
    single("What is the height of a complete binary tree with 15 nodes (root at height 0)?", ["2", "3", "4", "15"], "B"),
    single("Which operation is O(1) on a doubly linked list given a node reference?", ["Search", "Delete that node", "Sort", "Find the middle"], "B"),
    single("A min-heap is stored in an array. Where is the smallest element?", ["The last index", "Index 0", "The middle", "Anywhere"], "B"),
    numeric("How many edges does a tree with 20 vertices have?", "19"),
    numeric("A queue starts empty. After enqueue 5, enqueue 8, dequeue, enqueue 2, how many elements does it hold?", "2"),
    numeric("What is the maximum number of nodes in a binary tree of height 3 (root at height 0)?", "15"),
    numeric("In 0-based array indexing of a binary heap, what is the index of the left child of index 4?", "9"),
]


def published_version(a):
    """The published version: "published", or "current" when the current version is the published one (null fields omitted)."""
    v = a.get("published") or (a["current"] if a.get("current", {}).get("state") == "PUBLISHED" else None)
    return v["id"] if v else None


def seed(ctx):
    bank = ctx.api("POST", "/api/v1/question-banks", {"orgUnitId": CSE, "code": "DS-CORE", "name": "Data Structures (core)",
                                                       "intendedUses": ["ACADEMIC"], "policy": {"approvalPolicy": "STANDARD", "requiredReviews": 1,
                                                                                               "approverDistinct": True}})["id"]
    ctx.put("bank", "DS-CORE", bank)
    approved = []
    for i, q in enumerate(QUESTIONS):
        qid = ctx.api("POST", f"/api/v1/question-banks/{bank}/questions", q, as_="faculty")["id"]
        ctx.api("POST", f"/api/v1/questions/{qid}/submit", {}, as_="faculty")
        ctx.api("POST", f"/api/v1/questions/{qid}/reviews", {"decision": "REVIEWED", "comment": None, "checklist": {"accurate": True}}, as_="designer")
        ctx.api("POST", f"/api/v1/questions/{qid}/approve", {"reason": "Checked against the syllabus"}, as_="controller")
        approved.append(ctx.api("GET", f"/api/v1/questions/{qid}", as_="designer"))
    # the approved version is "approved", or "current" when the current version is the approved one (null fields are omitted)
    versions = [(q.get("approved") or (q["current"] if q["current"].get("state") == "APPROVED" else {})).get("id") for q in approved]
    if None in versions:
        raise spec.StepError("approved questions carry no approvedVersionId: " + json.dumps(approved[0])[:300])

    paper = ctx.api("POST", "/api/v1/assessments", {"orgUnitId": CSE, "code": "DS-MID-2026", "purpose": "INTERNAL",
                                                     "title": "Data Structures — mid-term", "durationMinutes": 45}, as_="designer")
    aid = paper["id"]
    lock = ctx.api("GET", f"/api/v1/assessments/{aid}", as_="designer")
    version = lock["current"]["version"]
    ctx.api("PUT", f"/api/v1/assessments/{aid}/draft", {"title": "Data Structures — mid-term", "durationMinutes": 45, "version": version,
                                                         "items": [{"questionVersionId": v} for v in versions]}, as_="designer")
    ctx.api("POST", f"/api/v1/assessments/{aid}/submit", {}, as_="designer")
    ctx.api("POST", f"/api/v1/assessments/{aid}/reviews", {"decision": "REVIEWED"}, as_="controller2")
    ctx.api("POST", f"/api/v1/assessments/{aid}/publish", {}, as_="controller")
    published = ctx.api("GET", f"/api/v1/assessments/{aid}", as_="designer")
    ctx.put("assessment", "DS-MID-2026", aid)
    if not published_version(published):
        raise spec.StepError("DS-MID-2026 was not published: " + json.dumps(published)[:300])
    ctx.put("assessment_version", "DS-MID-2026", published_version(published))
    return f"{len(versions)} approved questions; DS-MID-2026 published"


def verify(ctx):
    aid = ctx.get("assessment", "DS-MID-2026")
    a = ctx.api("GET", f"/api/v1/assessments/{aid}", as_="designer")
    if published_version(a) != ctx.get("assessment_version", "DS-MID-2026"):
        raise spec.StepError("DS-MID-2026 has no published version: " + json.dumps(a)[:300])
    bank = ctx.get("bank", "DS-CORE")
    listed = ctx.api("GET", f"/api/v1/question-banks/{bank}/questions?size=100", as_="designer")
    items = listed["items"] if isinstance(listed, dict) else listed
    approved = [q for q in items if q.get("state") == "APPROVED"]
    if len(approved) != len(QUESTIONS):
        raise spec.StepError(f"expected {len(QUESTIONS)} approved questions, found {len(approved)}")
    return f"{len(approved)} approved questions; DS-MID-2026 published"
