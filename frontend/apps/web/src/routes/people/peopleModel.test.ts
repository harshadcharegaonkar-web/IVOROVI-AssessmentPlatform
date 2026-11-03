import { describe, expect, it } from "vitest";
import { statusActions } from "./peopleModel";

describe("people model", () => {
  it("offers only the transitions the API allows", () => {
    expect(statusActions("ACTIVE")).toEqual(["SUSPEND", "DEACTIVATE"]);
    expect(statusActions("SUSPENDED")).toEqual(["REACTIVATE", "DEACTIVATE"]);
    expect(statusActions("INVITED")).toEqual(["DEACTIVATE"]);
    expect(statusActions("DEACTIVATED")).toEqual(["ACTIVATE"]);
  });

});
