"""Tier-1 reset of the demo institution (contract: docs/demo-programme/demo-data.md, "Reset and backup").

reset_institution() removes every row of the demo organization (all tables with tenant_id, triggers off for the
duration) and its Valkey keys; Keycloak sign-ins are kept and reused. Only the meridian-demo organization is touched.
The fast tier (restore of the golden snapshot, then live()) is added in scripts/demo/snapshot.py.
"""
def reset_institution():
    import demo_data  # needs the local .env
    demo_data.reset()
