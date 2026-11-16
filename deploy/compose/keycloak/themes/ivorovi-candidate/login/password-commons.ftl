<#import "field.ftl" as field>
<#-- KI-79: never optional for candidates. Changing the password ends every other session at once; the candidate is told. -->
<#macro logoutOtherSessions>
    <div id="kc-form-options" class="${properties.kcFormOptionsClass!}">
        <input type="hidden" id="logout-sessions" name="logout-sessions" value="on">
        <p id="logout-sessions-notice" class="${properties.kcFormHelperTextClass!}">${msg("ivoroviLogoutOtherSessionsNotice")}</p>
    </div>
</#macro>
